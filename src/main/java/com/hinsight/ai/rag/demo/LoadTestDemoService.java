package com.hinsight.ai.rag.demo;

import com.hinsight.ai.embedding.EmbeddingService;
import com.hinsight.ai.embedding.Vectors;
import com.hinsight.ai.rag.cache.SemanticAnswerCache;
import com.hinsight.ai.rag.guard.QuestionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * <b>1만건 토큰 부하 벤치마크(실측)</b>.
 *
 * <p>라이브 방송 채팅 스트림을 합성해 <b>실서비스 파이프라인 그대로</b> 통과시킨다:
 * {@link QuestionGuard}(Aho-Corasick 1차 가드) → {@link EmbeddingService}(BGE-M3 실측 임베딩) →
 * 코사인 0.85 그리디 군집화({@link com.hinsight.ai.rag.SemanticQuestionAggregator} 와 동일 방식) →
 * {@link SemanticAnswerCache}(Redis, cos 0.92) 까지. Gemini 만 실제 호출하지 않고(비용·레이트리밋)
 * 호출 <b>횟수</b>를 실측, 토큰은 {@code approxTokens}(≈700/호출)로 환산한다.</p>
 *
 * <p>입력은 색/사이즈 슬롯·표현변형으로 문구를 다양화하되, <b>한 상품 세션의 현실적 의도 수(~40종)</b> 안에 둔다
 * (캐시 상한 {@code max-entries-per-product=50} 아래라 재트리거가 정상 HIT 된다).
 * 또한 임계치를 0.80~0.95로 스윕해 군집수·호출수 변화를 함께 반환한다(임계치 선택이 임의가 아님을 보여주는 근거).</p>
 */
@Service
public class LoadTestDemoService {

    private static final Logger log = LoggerFactory.getLogger(LoadTestDemoService.class);

    private static final int MAX_COUNT = 50_000;
    private static final int PER_CALL_TOKENS = 700;   // 컨텍스트(≈650)+답변(≈50) approxTokens 근사
    private static final int PROMPT_CHARS = 2000;     // rag.context.max-total-chars

    private static final double SIM_THRESHOLD = 0.85; // 군집 흡수 코사인 하한(운영 기본)
    private static final int TRIGGER_THRESHOLD = 3;   // rag.bot.threshold
    private static final long COOLDOWN_MS = 300_000;  // rag.bot.cooldown-seconds(300s)
    private static final double JUNK_RATE = 0.18;
    private static final int SERIES_POINTS = 48;
    private static final double[] SWEEP = {0.80, 0.83, 0.85, 0.88, 0.90, 0.92, 0.95};

    private final QuestionGuard guard;
    private final EmbeddingService embeddingService;
    private final SemanticAnswerCache cache;

    private final List<Topic> pool = new ArrayList<>();
    private final int[] weightTable;
    private final String[] junk;

    public LoadTestDemoService(QuestionGuard guard, EmbeddingService embeddingService, SemanticAnswerCache cache) {
        this.guard = guard;
        this.embeddingService = embeddingService;
        this.cache = cache;

        // ---- 합성 질문 풀 (한 상품 라이브 세션 = 서로 다른 의도 ~40종, 캐시 상한 50 아래) ----
        // 색/사이즈 슬롯 × 표현변형으로 문구는 다양하되, 한 상품의 현실적 의도 수를 넘지 않게 유지한다.
        String[] colors = {"검정", "네이비", "베이지"};
        String[] sizes = {"S", "M", "L"};

        pool.add(new Topic("색상", 20, expand(colors, new String[]{
                "%s 어두운가요?", "%s 실물 색 어때요?"})));                                  // 6
        pool.add(new Topic("사이즈", 16, join(
                expand(sizes, new String[]{"%s 입어도 되나요?"}),
                new String[]{"핏 크게 나왔나요?", "사이즈표 어디 있어요?", "정사이즈인가요?"}))); // 6
        pool.add(new Topic("배송", 18, new String[]{
                "배송 언제 와요?", "언제쯤 배송되나요?", "배송 얼마나 걸려요?",
                "배송비 얼마예요?", "배송언제와요??"}));                                      // 5
        pool.add(new Topic("가격", 12, new String[]{
                "더 싸게 안 되나요?", "할인 없나요?", "쿠폰 적용돼요?", "지금이 제일 싼가요?"})); // 4
        pool.add(new Topic("재질", 9, new String[]{
                "재질이 뭐예요?", "소재 어떤가요?", "비침 있나요?", "신축성 있어요?"}));         // 4
        pool.add(new Topic("재고", 9, new String[]{
                "이 색 재고 있나요?", "품절인가요?", "재입고 되나요?"}));                       // 3
        pool.add(new Topic("세탁", 6, new String[]{
                "세탁 어떻게 해요?", "물세탁 되나요?", "드라이 해야 하나요?"}));                // 3
        pool.add(new Topic("기타", 5, new String[]{
                "교환 환불 되나요?", "선물포장 되나요?", "AS 되나요?", "정품 맞나요?"}));          // 4

        this.weightTable = buildWeightTable();
        this.junk = new String[]{
                "ㅋㅋㅋㅋ", "안녕하세요~", "잘 보고 있어요", "대박이다", "1빠", "👍👍", "🔥🔥🔥",
                "호스트 바보냐?", "존나 비싸네", "시발 이걸 사라고?", "노잼", "형 사랑해"};
    }

    /**
     * 벤치마크 실행.
     * @param countOpt    투입 채팅 수(기본 10000, 상한 50000)
     * @param windowSecOpt 가상 방송 창(초, 기본 1800)
     * @param productsOpt 동시 방송 상품 세션 수(기본 3). 상품마다 캐시가 따로라 상품당 군집은 상한 50 아래로 유지된다.
     */
    public LoadTestResponse run(Integer countOpt, Integer windowSecOpt, Integer productsOpt) {
        int count = clamp(countOpt == null ? 10_000 : countOpt, 100, MAX_COUNT);
        int windowSec = clamp(windowSecOpt == null ? 600 : windowSecOpt, 60, 7200);
        int products = clamp(productsOpt == null ? 3 : productsOpt, 1, 12);
        long windowMs = windowSec * 1000L;

        // 상품마다 별도 캐시 키 + 별도 군집 리스트 (실서비스와 동일: 캐시는 상품별)
        long pidBase = -System.nanoTime();      // 실행마다 유일 → Redis 캐시 콜드 스타트 보장
        long[] cachePid = new long[products];
        List<List<Cluster>> clustersByP = new ArrayList<>();
        for (int p = 0; p < products; p++) { cachePid[p] = pidBase - p; clustersByP.add(new ArrayList<>()); }

        Random rnd = new Random(42);            // 결정적: 발표 때 매번 같은 수치

        // 서로 다른 질문(첫 등장 순서 유지) — 임베딩 캐시 + 임계치 스윕 재료
        LinkedHashMap<String, Dist> distinct = new LinkedHashMap<>();
        Map<String, int[]> topicStat = new LinkedHashMap<>();   // name -> [chats, triggers]
        for (Topic t : pool) topicStat.put(t.name, new int[]{0, 0});

        long guardNanos = 0, embedNanos = 0;
        int guardPassed = 0, guardDropped = 0, distinctEmbeds = 0;
        long triggers = 0, llmCalls = 0, cacheHits = 0;

        List<LoadTestResponse.Point> series = new ArrayList<>();
        long bucketMs = Math.max(1, windowMs / SERIES_POINTS);
        long nextMark = bucketMs;

        long t0 = System.currentTimeMillis();

        for (int i = 0; i < count; i++) {
            long arrivalMs = (long) ((double) i / count * windowMs);
            Gen g = nextChat(rnd);

            long gs = System.nanoTime();
            QuestionGuard.Decision d = guard.classify(g.text);
            guardNanos += System.nanoTime() - gs;

            if (d != QuestionGuard.Decision.ACCEPT) {
                guardDropped++;
            } else {
                guardPassed++;
                if (g.topic != null) topicStat.get(g.topic)[0]++;

                Dist dq = distinct.get(g.text);
                if (dq == null) {
                    long es = System.nanoTime();
                    float[] vec = embeddingService.embed(g.text);
                    embedNanos += System.nanoTime() - es;
                    dq = new Dist(vec, g.topic);
                    distinct.put(g.text, dq);
                    distinctEmbeds++;
                }
                dq.count++;
                float[] vec = dq.vec;

                int pi = i % products;                       // 상품 배정(라운드로빈)
                List<Cluster> clusters = clustersByP.get(pi);

                // 그리디 군집화(코사인 ≥ 0.85) — 집계기와 동일. 상품 세션별로 독립.
                Cluster best = null;
                double bestSim = -1;
                for (Cluster b : clusters) {
                    double s = Vectors.cosine(vec, b.seedVec);
                    if (s > bestSim) { bestSim = s; best = b; }
                }
                Cluster target;
                if (best != null && bestSim >= SIM_THRESHOLD) {
                    target = best;
                    target.count++;
                } else {
                    target = new Cluster(vec, g.text, g.topic);
                    clusters.add(target);
                }
                target.sinceAnswer++;

                // 트리거: 마지막 답변 이후 새 질문이 threshold 건 이상 쌓이고 cooldown 경과 → RAG(캐시 조회). 캐시는 상품별.
                if (target.sinceAnswer >= TRIGGER_THRESHOLD
                        && (target.answeredAtMs < 0 || arrivalMs - target.answeredAtMs > COOLDOWN_MS)) {
                    target.answeredAtMs = arrivalMs;
                    target.sinceAnswer = 0;
                    triggers++;
                    if (target.topic != null) topicStat.get(target.topic)[1]++;

                    Optional<SemanticAnswerCache.Hit> hit = cache.lookup(cachePid[pi], target.seedVec);
                    if (hit.isPresent()) {
                        cacheHits++;                 // 이전 답변 재사용 → Gemini 호출 0
                    } else {
                        llmCalls++;                  // 실제 Gemini 호출 지점
                        cache.store(cachePid[pi], target.seedText, target.seedVec,
                                "[benchmark] " + target.seedText + " 에 대한 합성 답변", PROMPT_CHARS);
                    }
                }
            }

            while (arrivalMs >= nextMark && series.size() < SERIES_POINTS) {
                series.add(new LoadTestResponse.Point((int) (nextMark / 1000), i + 1, triggers, llmCalls));
                nextMark += bucketMs;
            }
        }
        series.add(new LoadTestResponse.Point(windowSec, count, triggers, llmCalls));

        // ---- 임계치 민감도 스윕 (같은 임베딩으로 재군집화, 상품 수만큼 배수) ----
        List<Dist> ds = new ArrayList<>(distinct.values());
        List<LoadTestResponse.Sensitivity> sensitivity = new ArrayList<>();
        for (double th : SWEEP) {
            int[] r = clusterAt(ds, th);           // [군집수, 트리거되는 군집수] (상품 1개 기준)
            sensitivity.add(new LoadTestResponse.Sensitivity(th, r[0] * products, (long) r[1] * products, th == SIM_THRESHOLD));
        }

        int totalClusters = 0;
        for (List<Cluster> cl : clustersByP) totalClusters += cl.size();

        long totalMs = Math.max(1, System.currentTimeMillis() - t0);
        long naiveTok = (long) count * PER_CALL_TOKENS;
        long clusterTok = triggers * PER_CALL_TOKENS;
        long bothTok = llmCalls * PER_CALL_TOKENS;
        double savings = naiveTok == 0 ? 0 : (1 - (double) bothTok / naiveTok) * 100.0;
        double hitRate = triggers == 0 ? 0 : (double) cacheHits / triggers;

        List<LoadTestResponse.Topic> topics = new ArrayList<>();
        for (Map.Entry<String, int[]> e : topicStat.entrySet()) {
            topics.add(new LoadTestResponse.Topic(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }

        log.info("[부하테스트] 채팅 {}건 · 상품 {}개 → 가드통과 {}, 서로다른질문 {}, 군집 {}, 트리거 {}, LLM호출 {}, 캐시HIT {} ({}ms, {}건/s)",
                count, products, guardPassed, distinctEmbeds, totalClusters, triggers, llmCalls, cacheHits, totalMs, count * 1000L / totalMs);

        return new LoadTestResponse(
                count, guardPassed, guardDropped, distinctEmbeds, totalClusters,
                count, triggers, llmCalls, cacheHits, hitRate,
                PER_CALL_TOKENS, naiveTok, clusterTok, bothTok, savings,
                guardNanos / 1_000_000, embedNanos / 1_000_000, totalMs, count * 1000L / totalMs,
                windowSec, products, series, topics, sensitivity);
    }

    /**
     * 서로 다른 질문 집합을 임계치 th 로 그리디 군집화하고, [군집 수, 트리거되는(≥threshold 누적) 군집 수]를 반환한다.
     * 스트리밍 군집화와 동일한 "첫 등장 순서 · 시드 기준" 이라 0.85 결과는 본 실행과 일치한다.
     */
    private int[] clusterAt(List<Dist> ds, double th) {
        List<Cluster> cs = new ArrayList<>();
        for (Dist dq : ds) {
            Cluster best = null;
            double bestSim = -1;
            for (Cluster c : cs) {
                double s = Vectors.cosine(dq.vec, c.seedVec);
                if (s > bestSim) { bestSim = s; best = c; }
            }
            if (best != null && bestSim >= th) {
                best.count += dq.count;
            } else {
                Cluster nc = new Cluster(dq.vec, null, null);
                nc.count = dq.count;
                cs.add(nc);
            }
        }
        int triggering = 0;
        for (Cluster c : cs) if (c.count >= TRIGGER_THRESHOLD) triggering++;
        return new int[]{cs.size(), triggering};
    }

    // ---------- 생성 ----------

    private int[] buildWeightTable() {
        int total = 0;
        for (Topic t : pool) total += t.weight;
        int[] table = new int[total];
        int p = 0;
        for (int ti = 0; ti < pool.size(); ti++) {
            for (int k = 0; k < pool.get(ti).weight; k++) table[p++] = ti;
        }
        return table;
    }

    private Gen nextChat(Random rnd) {
        if (rnd.nextDouble() < JUNK_RATE) {
            return new Gen(junk[rnd.nextInt(junk.length)], null);
        }
        Topic t = pool.get(weightTable[rnd.nextInt(weightTable.length)]);
        return new Gen(t.questions[rnd.nextInt(t.questions.length)], t.name);
    }

    /** 슬롯 값 × 템플릿(%s) → 문자열 배열. */
    private static String[] expand(String[] slots, String[] templates) {
        List<String> out = new ArrayList<>();
        for (String tpl : templates) for (String s : slots) out.add(String.format(tpl, s));
        return out.toArray(new String[0]);
    }

    private static String[] join(String[]... arrs) {
        List<String> out = new ArrayList<>();
        for (String[] a : arrs) for (String s : a) out.add(s);
        return out.toArray(new String[0]);
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private record Gen(String text, String topic) {
    }

    private static final class Topic {
        final String name;
        final int weight;
        final String[] questions;

        Topic(String name, int weight, String[] questions) {
            this.name = name;
            this.weight = weight;
            this.questions = questions;
        }
    }

    private static final class Dist {
        final float[] vec;
        final String topic;
        int count = 0;

        Dist(float[] vec, String topic) {
            this.vec = vec;
            this.topic = topic;
        }
    }

    private static final class Cluster {
        final float[] seedVec;
        final String seedText;
        final String topic;
        int count = 1;
        int sinceAnswer = 0;   // 마지막 답변(트리거) 이후 누적된 새 질문 수
        long answeredAtMs = -1;

        Cluster(float[] seedVec, String seedText, String topic) {
            this.seedVec = seedVec;
            this.seedText = seedText;
            this.topic = topic;
        }
    }
}
