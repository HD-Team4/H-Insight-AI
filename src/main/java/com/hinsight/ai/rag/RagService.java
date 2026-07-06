package com.hinsight.ai.rag;

import com.hinsight.ai.embedding.EmbeddingService;
import com.hinsight.ai.rag.cache.SemanticAnswerCache;
import com.hinsight.ai.vectorstore.ReviewMatch;
import com.hinsight.ai.vectorstore.ReviewSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 라이브 방송 리뷰 기반 Q&A RAG 파이프라인.
 * 질문 → BGE-M3(Ollama) 임베딩 → review_vectors 2단계 검색(공식 스펙 + 최신 리뷰)
 *      → 출처·날짜 태깅 컨텍스트 조립 → Gemini 답변 스트리밍(SSE).
 *
 * <p>'과거의 망령'(리뉴얼 전 구형 정보) 방지를 위해:
 * (1) 컷오프 이전 리뷰 차단, (2) 최신 리뷰에 시간 가중, (3) 공식 스펙을 컨텍스트 최상단 + 신뢰도 위계로 강제.</p>
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    // 컷오프 없음(공식 스펙 미등록)일 때 쓸 안전한 하한. OffsetDateTime.MIN 은 Postgres timestamptz 범위 밖이라 사용 불가.
    private static final OffsetDateTime NO_CUTOFF = OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private final EmbeddingService embeddingService;
    private final ReviewSearchService reviewSearchService;
    private final ChatClient chatClient;
    private final SemanticAnswerCache answerCache;

    // 랭킹 파라미터 (application.yml 로 튜닝: rag.*)
    @Value("${rag.official-top-k:2}")   private int officialTopK;
    @Value("${rag.review-top-k:5}")     private int reviewTopK;
    @Value("${rag.half-life-days:90}")  private double halfLifeDays;
    @Value("${rag.w-sim:0.6}")          private double wSim;
    @Value("${rag.w-rec:0.4}")          private double wRec;

    // 프롬프트 최적화 파라미터: 컨텍스트를 잘라 LLM 에 넘기는 토큰을 줄인다 (rag.context.*)
    @Value("${rag.context.max-official-chars:600}") private int maxOfficialChars;  // 공식 스펙 1건 최대 길이
    @Value("${rag.context.max-review-chars:280}")   private int maxReviewChars;    // 유저 리뷰 1건 최대 길이
    @Value("${rag.context.max-total-chars:2000}")   private int maxTotalChars;     // 조립된 컨텍스트 총 상한

    public RagService(EmbeddingService embeddingService,
                      ReviewSearchService reviewSearchService,
                      ChatClient chatClient,
                      SemanticAnswerCache answerCache) {
        this.embeddingService = embeddingService;
        this.reviewSearchService = reviewSearchService;
        this.chatClient = chatClient;
        this.answerCache = answerCache;
    }

    // LLM 이 "답하지 않기로" 결정했을 때 출력하는 단일 키워드. 백엔드는 이 값이면 Silent Drop 한다.
    static final String NO_ANSWER = "IGNORE";

    private static final String SYSTEM = """
            너는 라이브 커머스 방송의 실시간 상품 상담 챗봇이다. 아래 <context> 의 정보만 근거로 답한다.
            시청자에게만 노출되며, 진행자(호스트)에게 질문을 넘기는(에스컬레이션) 수단은 없다.

            # 최우선 규칙 — 답하지 말아야 할 때는 오직 'IGNORE' 만 출력 (그 무엇보다 우선)
            아래 중 하나라도 해당하면, 다른 어떤 문자도 출력하지 말고 대문자 다섯 글자 IGNORE 만 출력한다.
            사과·설명·인사·이모지·문장부호·따옴표 없이 정확히 IGNORE 만 출력한다.
            1) 상품/방송과 무관한 질문 (예: 날씨, 정치, 잡담, 진행자 신상, 다른 쇼핑몰 등).
            2) 비방·욕설·트롤링·도발성 질문 (예: "호스트 바보인가요?", 인신공격, 성적/혐오 표현).
            3) <context> 에 질문의 답이 될 근거가 없어, 지어내지 않고는 답할 수 없는 경우.
            → 이 방송에는 "정보가 없습니다"라고 공지하는 것이 오히려 최악의 UX 다. 모르면 반드시 IGNORE.

            # 정보 신뢰도 위계 (답변할 때 반드시 이 순서로 따른다)
            1순위: [공식 스펙] — 제조사가 제공한 리뉴얼/상세페이지 정보. 제품의 "현재 사실"이다.
            2순위: 최신 [유저 리뷰] — 날짜가 가까울수록 신뢰한다.
            3순위: 과거 [유저 리뷰] — 참고만 하되, 리뉴얼로 바뀐 사양일 수 있으므로 단정에 쓰지 않는다.

            # 충돌 해결 규칙
            - [공식 스펙]과 [유저 리뷰]가 충돌하면 → 무조건 [공식 스펙]을 사실로 안내한다.
            - 리뷰가 스펙과 다른 불만을 말하면, 그것을 "현재 사양"으로 오인해 안내하지 마라.
              대신 "리뉴얼 이전 제품 후기일 수 있다"는 취지로 구분해 전달한다.
            - 오래된 리뷰끼리 최신 리뷰와 상충하면 최신 리뷰를 우선한다.

            # 답변 원칙 (위 IGNORE 조건에 해당하지 않을 때만)
            - <context> 에 근거가 없는 사양·수치·효능은 절대 지어내지 마라. (근거 없으면 IGNORE)
            - 방송 공지처럼 전달한다: "고객님" 같은 개인 호칭·인사말·권유 문구를 쓰지 말고,
              리뷰를 종합해 사실만 안내하듯 알린다. (예: "…라는 후기가 많습니다", "공식 스펙 기준 …입니다")
            - 1~2문장, 간결한 '~합니다/~입니다' 공지체. 마크다운/이모지 금지.
            - 스펙 질문에는 [공식 스펙] 근거를 우선 인용하고, 사용감·만족도는 최신 리뷰로 보강한다.
            """;

    /**
     * 리뉴얼 컷오프 결정. 우선순위: 요청 명시값 > 공식 스펙 게시일(자동) > null(필터 없음).
     *
     * @param explicit 요청에서 넘어온 명시 컷오프(없으면 null)
     * @return 실제 적용할 컷오프. null 이면 날짜 필터 없이 전체 리뷰 대상.
     */
    public OffsetDateTime resolveCutoff(long productId, OffsetDateTime explicit) {
        if (explicit != null) return explicit;
        return reviewSearchService.findRenewalCutoff(productId);   // 공식 스펙 없으면 null
    }

    /**
     * LLM 응답이 "무응답(Silent Drop 대상)"인지 판정한다.
     * 비어 있거나, 앞뒤 따옴표·마침표를 떼고 대소문자 무시하여 {@code IGNORE} 면 무응답으로 본다.
     * 호출측(봇/컨트롤러)은 이 경우 채팅에 아무것도 내보내지 않는다.
     */
    public static boolean isNoAnswer(String answer) {
        if (answer == null) return true;
        String t = answer.strip().replaceAll("^[\"'`\\s]+|[\"'`.\\s]+$", "");
        return t.isEmpty() || t.equalsIgnoreCase(NO_ANSWER);
    }

    /**
     * 질문에 대한 답변을 토큰 스트림으로 생성.
     *
     * @param productId 방송 중인 상품 ID
     * @param question  방송 댓글에서 탐지된 질문
     * @param cutoff    적용할 리뉴얼 기준일(이전 리뷰 차단). null 이면 필터 없이 전체 리뷰 대상.
     *                  {@link #resolveCutoff}로 미리 결정해 넘긴다.
     */
    public Flux<String> answer(long productId, String question, OffsetDateTime cutoff) {
        float[] qVec = embeddingService.embed(question);

        // 1) 시맨틱 캐시 조회: 유사 질문을 이미 답했으면 LLM 호출 없이 재사용(토큰 절감)
        Optional<SemanticAnswerCache.Hit> cached = answerCache.lookup(productId, qVec);
        if (cached.isPresent()) {
            log.info("[RAG] 캐시 재사용 productId={}, q='{}' (LLM 호출 skip)", productId, question);
            return Flux.just(cached.get().answer());
        }

        OffsetDateTime effectiveCutoff = (cutoff != null) ? cutoff : NO_CUTOFF;
        List<ReviewMatch> official = reviewSearchService.searchOfficial(productId, qVec, officialTopK);
        List<ReviewMatch> reviews  = reviewSearchService.searchReviews(
                productId, qVec, effectiveCutoff, reviewTopK, halfLifeDays, wSim, wRec);

        String context = buildContext(official, reviews);
        if (!StringUtils.hasText(context)) {
            // 근거가 아예 없으면 지어내지 않는다 → IGNORE. 호출측(봇)이 Silent Drop 한다.
            return Flux.just(NO_ANSWER);
        }

        String user = """
                질문: %s

                <context>
                %s
                </context>
                """.formatted(question, context);

        // 2) 캐시 미스 → LLM 스트리밍. 토큰을 누적해 완료 시 캐시에 적재한다.
        //    단, LLM 이 IGNORE(무응답) 를 냈으면 캐시하지 않는다(무응답을 캐싱하면 재질문도 계속 무응답 처리됨).
        int promptChars = SYSTEM.length() + user.length();
        StringBuilder acc = new StringBuilder();
        return chatClient.prompt()
                .system(SYSTEM)
                .user(user)
                .stream()
                .content()
                .doOnNext(acc::append)
                .doOnComplete(() -> {
                    if (!isNoAnswer(acc.toString())) {
                        answerCache.store(productId, question, qVec, acc.toString(), promptChars);
                    } else {
                        log.info("[RAG] LLM IGNORE → 캐시 미적재 productId={}, q='{}'", productId, question);
                    }
                })
                .onErrorResume(e -> {
                    log.warn("[RAG] 답변 생성 실패, 폴백: {} ({})", question, e.getMessage());
                    return Flux.just("지금 답변을 준비하지 못했어요. 잠시 후 다시 문의해 주세요.");
                });
    }

    /**
     * 공식 스펙을 최상단에, 그 아래 최신 리뷰 순으로 출처·날짜를 태깅해 조립.
     *
     * <p>프롬프트 최적화: 각 블록을 유형별 최대 길이로 자르고(공식 스펙은 더 넉넉히),
     * 조립 총량이 {@code rag.context.max-total-chars} 를 넘으면 더 낮은 우선순위 리뷰를 버린다.
     * 공식 스펙(1순위)이 리뷰 때문에 잘리지 않도록 스펙을 먼저 채운다.</p>
     */
    private String buildContext(List<ReviewMatch> official, List<ReviewMatch> reviews) {
        List<String> blocks = new ArrayList<>();
        int budget = maxTotalChars;

        for (ReviewMatch m : official) {
            String block = "[공식 스펙 | %s]\n%s".formatted(fmt(m.writtenAt()), truncate(m.content(), maxOfficialChars));
            if (block.length() > budget) break;
            blocks.add(block);
            budget -= block.length();
        }
        for (ReviewMatch m : reviews) {
            String block = "[유저 리뷰 | %s]\n%s".formatted(fmt(m.writtenAt()), truncate(m.content(), maxReviewChars));
            if (block.length() > budget) break;   // 예산 초과분 리뷰는 컷오프(토큰 절약)
            blocks.add(block);
            budget -= block.length();
        }
        return String.join("\n\n", blocks);
    }

    /** 본문을 max 자로 자르고, 잘린 경우 말줄임표를 붙인다. */
    private String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max).stripTrailing() + "…";
    }

    private String fmt(OffsetDateTime dt) {
        return dt == null ? "날짜미상" : dt.format(DATE);
    }
}
