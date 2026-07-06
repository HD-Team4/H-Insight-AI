package com.hinsight.ai.rag.guard.demo;

import org.ahocorasick.trie.Emit;
import org.ahocorasick.trie.Trie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>1차 방어(사전 게이팅) 시연용</b> 서비스. 실서비스 경로({@link com.hinsight.ai.rag.guard.QuestionGuard})와
 * 완전히 분리되어 있으며, 오직 {@code /demo/guard} 시각화 페이지를 위한 스캔 결과(매칭 위치·소요 시간)를 제공한다.
 *
 * <p>실서비스의 {@code QuestionGuard} 는 판정 결과(ACCEPT/DROP)만 반환하면 되지만, 데모에서는
 * "어느 금칙어가 입력의 몇 번째 글자에서 걸렸는가"와 "몇 마이크로초가 걸렸는가"를 눈으로 보여줘야 하므로
 * 동일한 사전({@code classpath:rag/badwords.txt})과 동일한 정규화 규칙을 그대로 사용해 Trie 를 따로 구축한다.</p>
 *
 * <p>핵심 어필: 수만 개 금칙어라도 정규식/contains 반복(O(N·M))이 아니라 Aho-Corasick 의 단일 스캔(O(N))으로
 * 처리된다. 이 서비스는 그 스캔의 실제 소요 시간을 나노초 단위로 측정해 응답에 담는다.</p>
 */
@Service
public class GuardDemoService {

    private static final Logger log = LoggerFactory.getLogger(GuardDemoService.class);

    /** 사전 로딩 실패 시 폴백 (QuestionGuard 와 동일 취지의 최소 방어). */
    private static final List<String> FALLBACK = List.of(
            "바보", "멍청", "병신", "시발", "씨발", "존나", "개새", "꺼져", "죽어", "닥쳐", "쓰레기", "지랄");

    /** 벤치마크용 "정상(clean)" 입력 — 금칙어가 없어 두 방식 모두 사전 전체를 끝까지 훑는다(=흔한 케이스). */
    private static final String BENCH_SAMPLE =
            "이번 신상 후기 진짜 좋네요 배송도 빠르고 색상도 예쁘고 재질도 만족스러워서 재구매 의사 있어요 추천합니다";

    /** 금칙어 개수(M) 스윕 구간. 사전을 합성 키워드로 부풀려 M 증가에 따른 확장성을 보여준다. */
    private static final int[] BENCH_SIZES = {50, 500, 2000, 10000, 50000};

    /** 합성 키워드 생성용 음절 알파벳(BENCH_SAMPLE 에 등장하지 않아 매칭이 안 됨). */
    private static final char[] SYL = {
            '가', '나', '다', '라', '마', '바', '사', '아', '자', '차',
            '카', '타', '파', '하', '거', '너', '더', '러', '머', '서'};

    private final Trie trie;               // 불변·스레드세이프, 부팅 시 1회 구축
    private final List<String> dictionary; // 시각화(프론트 Trie 렌더)용 원본 키워드

    /** 벤치마크용 키워드 풀(실사전 + 합성). 첫 호출 시 지연 생성 후 캐시. */
    private volatile List<String> benchPool;

    /** JIT 의 죽은 코드 제거(DCE) 방지용 싱크. */
    @SuppressWarnings("unused")
    private volatile boolean sink;

    public GuardDemoService(
            @Value("${rag.guard.badwords-resource:rag/badwords.txt}") String badwordsResource) {
        this.dictionary = loadDictionary(badwordsResource);
        // QuestionGuard 와 동일하게: 대소문자 무시, whole-word 아님(한국어 부분일치 목적)
        this.trie = Trie.builder().ignoreCase().addKeywords(dictionary).build();
        log.info("[가드-데모] 금칙어 사전 로딩 완료: {}개 (source={})", dictionary.size(), badwordsResource);
    }

    /** 프론트에서 Trie 를 그리기 위한 사전(정규화된 소문자 키워드) 스냅샷. */
    public List<String> dictionary() {
        return Collections.unmodifiableList(dictionary);
    }

    /**
     * 입력 1건을 Aho-Corasick 로 단일 스캔하고, 매칭 위치·차단 여부·소요 시간을 함께 반환한다.
     * 매칭 위치(start/end)는 정규화된 문자열 기준이다(공백·문장부호 제거 후 인덱스).
     */
    public GuardScanResponse scan(String rawInput) {
        String raw = rawInput == null ? "" : rawInput;
        String normalized = normalize(raw);

        // 실제 스캔 구간만 측정 (사전 로딩/정규화는 제외 — 순수 매칭 비용을 보여주기 위함)
        long t0 = System.nanoTime();
        Collection<Emit> emits = trie.parseText(normalized);
        long elapsedNanos = System.nanoTime() - t0;

        List<GuardScanResponse.Match> matches = new ArrayList<>();
        for (Emit e : emits) {
            // Emit 의 end 는 마지막 글자 인덱스(포함) → 프론트 하이라이트를 위해 +1(exclusive)로 보정
            matches.add(new GuardScanResponse.Match(e.getKeyword(), e.getStart(), e.getEnd() + 1));
        }

        boolean blocked = !matches.isEmpty();
        return new GuardScanResponse(
                raw,
                normalized,
                blocked,
                blocked ? "DROP_ABUSE" : "PASS",
                matches,
                elapsedNanos / 1000.0,   // → 마이크로초(소수 유지)
                normalized.length(),
                dictionary.size()
        );
    }

    /**
     * "도입 전(단순 contains 반복) vs 도입 후(Aho-Corasick)" 를 실제로 측정한다.
     * 금칙어 개수(M)를 {@link #BENCH_SIZES} 로 늘려가며 동일한 입력을 각각 스캔하고 중앙값을 취한다.
     *
     * <p>naive 는 매 반복마다 사전 전체를 {@code String.contains} 로 훑어 O(N·M) 을 재현하고,
     * Aho-Corasick 은 (앱 부팅 때처럼) 미리 만들어 둔 Trie 로 {@code parseText} 1회만 수행해 O(N) 을 재현한다.
     * Trie 구축 비용은 요청당 반복 비용이 아니므로(부팅 시 1회 상환) 측정에서 제외한다.</p>
     */
    public BenchmarkResponse benchmark() {
        final String sample = normalize(BENCH_SAMPLE);
        final int warmup = 20, iters = 120;
        List<String> pool = benchPool();

        List<BenchmarkResponse.Point> points = new ArrayList<>();
        for (int size : BENCH_SIZES) {
            List<String> subset = pool.subList(0, Math.min(size, pool.size()));
            Trie t = Trie.builder().ignoreCase().addKeywords(subset).build();

            double naive = measureNaive(sample, subset, warmup, iters);
            double aho = measureAho(sample, t, warmup, iters);
            double speedup = naive / Math.max(aho, 0.05);   // 타이머 해상도로 aho≈0 이어도 안전
            points.add(new BenchmarkResponse.Point(subset.size(), naive, aho, speedup));
        }
        return new BenchmarkResponse(sample.length(), iters, points);
    }

    /** 단순 contains 반복(O(N·M)) 측정. 조기 종료 없이 사전 전체를 훑는다(정상 문장의 흔한 경로). */
    private double measureNaive(String text, List<String> words, int warmup, int iters) {
        for (int w = 0; w < warmup; w++) sink = scanNaive(text, words);
        double[] samples = new double[iters];
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            boolean hit = scanNaive(text, words);
            samples[i] = (System.nanoTime() - t0) / 1000.0;   // µs
            sink = hit;
        }
        return median(samples);
    }

    private boolean scanNaive(String text, List<String> words) {
        boolean hit = false;
        for (String k : words) if (text.contains(k)) hit = true;  // break 하지 않음 → 최악/평균 O(N·M)
        return hit;
    }

    /** Aho-Corasick 단일 스캔(O(N)) 측정. Trie 는 이미 구축돼 있다고 가정(부팅 시 상환). */
    private double measureAho(String text, Trie t, int warmup, int iters) {
        for (int w = 0; w < warmup; w++) sink = !t.parseText(text).isEmpty();
        double[] samples = new double[iters];
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            boolean hit = !t.parseText(text).isEmpty();
            samples[i] = (System.nanoTime() - t0) / 1000.0;   // µs
            sink = hit;
        }
        return median(samples);
    }

    private double median(double[] a) {
        double[] c = a.clone();
        Arrays.sort(c);
        int n = c.length;
        return n % 2 == 1 ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2.0;
    }

    /** 실사전 + 합성 키워드로 최대 M 개까지 채운 벤치마크 풀. 지연 생성 후 캐시(더블 체크). */
    private List<String> benchPool() {
        List<String> local = benchPool;
        if (local != null) return local;
        synchronized (this) {
            if (benchPool != null) return benchPool;
            int target = BENCH_SIZES[BENCH_SIZES.length - 1];
            String sample = normalize(BENCH_SAMPLE);
            Set<String> set = new LinkedHashSet<>(dictionary);   // 실제 금칙어 먼저
            long idx = 0;
            while (set.size() < target) {
                String w = synthWord(idx++);
                if (!sample.contains(w)) set.add(w);             // 입력에 우연히 매칭되는 합성어는 제외
            }
            benchPool = new ArrayList<>(set);
            log.info("[가드-데모] 벤치마크 키워드 풀 생성: {}개 (실사전 {} + 합성)", benchPool.size(), dictionary.size());
            return benchPool;
        }
    }

    /** 인덱스를 {@link #SYL} 진법으로 펼쳐 4음절 합성 키워드를 만든다(서로 유일). */
    private String synthWord(long idx) {
        int base = SYL.length;                                   // 20^4 = 160,000 > 50,000
        char[] cs = new char[4];
        long n = idx;
        for (int i = 3; i >= 0; i--) { cs[i] = SYL[(int) (n % base)]; n /= base; }
        return new String(cs);
    }

    /** QuestionGuard 와 동일한 정규화: 소문자화 + 공백/문장부호 제거("시 발", "시-발" 같은 우회 표기 차단). */
    private String normalize(String s) {
        return s.strip().toLowerCase().replaceAll("[\\s?!.~。？！\\-_*]+", "");
    }

    /** classpath 사전 로딩. '#' 주석·빈 줄 무시, 소문자 정규화. 실패 시 폴백. */
    private List<String> loadDictionary(String resourcePath) {
        List<String> words = new ArrayList<>();
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String w = line.strip().toLowerCase();
                if (w.isEmpty() || w.startsWith("#")) continue;
                words.add(w);
            }
        } catch (Exception e) {
            log.warn("[가드-데모] 금칙어 사전 로딩 실패, 폴백 목록 사용: {} ({})", resourcePath, e.getMessage());
            return new ArrayList<>(FALLBACK);
        }
        return words.isEmpty() ? new ArrayList<>(FALLBACK) : words;
    }
}
