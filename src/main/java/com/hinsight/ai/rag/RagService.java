package com.hinsight.ai.rag;

import com.hinsight.ai.embedding.EmbeddingService;
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

    // 랭킹 파라미터 (application.yml 로 튜닝: rag.*)
    @Value("${rag.official-top-k:2}")   private int officialTopK;
    @Value("${rag.review-top-k:5}")     private int reviewTopK;
    @Value("${rag.half-life-days:90}")  private double halfLifeDays;
    @Value("${rag.w-sim:0.6}")          private double wSim;
    @Value("${rag.w-rec:0.4}")          private double wRec;

    public RagService(EmbeddingService embeddingService,
                      ReviewSearchService reviewSearchService,
                      ChatClient chatClient) {
        this.embeddingService = embeddingService;
        this.reviewSearchService = reviewSearchService;
        this.chatClient = chatClient;
    }

    private static final String SYSTEM = """
            너는 라이브 커머스 방송의 실시간 상품 상담 챗봇이다. 아래 <context> 의 정보만 근거로 답한다.

            # 정보 신뢰도 위계 (반드시 이 순서로 따른다)
            1순위: [공식 스펙] — 제조사가 제공한 리뉴얼/상세페이지 정보. 제품의 "현재 사실"이다.
            2순위: 최신 [유저 리뷰] — 날짜가 가까울수록 신뢰한다.
            3순위: 과거 [유저 리뷰] — 참고만 하되, 리뉴얼로 바뀐 사양일 수 있으므로 단정에 쓰지 않는다.

            # 충돌 해결 규칙
            - [공식 스펙]과 [유저 리뷰]가 충돌하면 → 무조건 [공식 스펙]을 사실로 안내한다.
            - 리뷰가 스펙과 다른 불만을 말하면, 그것을 "현재 사양"으로 오인해 안내하지 마라.
              대신 "리뉴얼 이전 제품 후기일 수 있다"는 취지로 구분해 전달한다.
            - 오래된 리뷰끼리 최신 리뷰와 상충하면 최신 리뷰를 우선한다.

            # 답변 원칙
            - <context> 에 근거가 없는 사양·수치·효능은 절대 지어내지 마라. 없으면 "확인된 정보가 없다"고 답한다.
            - 방송 시청자 대상이므로 1~3문장으로 간결하고 친근하게. 존댓말. 마크다운/이모지 금지.
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
     * 질문에 대한 답변을 토큰 스트림으로 생성.
     *
     * @param productId 방송 중인 상품 ID
     * @param question  방송 댓글에서 탐지된 질문
     * @param cutoff    적용할 리뉴얼 기준일(이전 리뷰 차단). null 이면 필터 없이 전체 리뷰 대상.
     *                  {@link #resolveCutoff}로 미리 결정해 넘긴다.
     */
    public Flux<String> answer(long productId, String question, OffsetDateTime cutoff) {
        float[] qVec = embeddingService.embed(question);

        OffsetDateTime effectiveCutoff = (cutoff != null) ? cutoff : NO_CUTOFF;
        List<ReviewMatch> official = reviewSearchService.searchOfficial(productId, qVec, officialTopK);
        List<ReviewMatch> reviews  = reviewSearchService.searchReviews(
                productId, qVec, effectiveCutoff, reviewTopK, halfLifeDays, wSim, wRec);

        String context = buildContext(official, reviews);
        if (!StringUtils.hasText(context)) {
            return Flux.just("해당 상품에 대해 확인된 정보가 아직 없어요. 잠시 후 다시 문의해 주세요.");
        }

        String user = """
                질문: %s

                <context>
                %s
                </context>
                """.formatted(question, context);

        return chatClient.prompt()
                .system(SYSTEM)
                .user(user)
                .stream()
                .content()
                .onErrorResume(e -> {
                    log.warn("[RAG] 답변 생성 실패, 폴백: {} ({})", question, e.getMessage());
                    return Flux.just("지금 답변을 준비하지 못했어요. 잠시 후 다시 문의해 주세요.");
                });
    }

    /** 공식 스펙을 최상단에, 그 아래 최신 리뷰 순으로 출처·날짜를 태깅해 조립. */
    private String buildContext(List<ReviewMatch> official, List<ReviewMatch> reviews) {
        List<String> blocks = new ArrayList<>();
        for (ReviewMatch m : official) {
            blocks.add("[공식 스펙 | %s]\n%s".formatted(fmt(m.writtenAt()), m.content()));
        }
        for (ReviewMatch m : reviews) {
            blocks.add("[유저 리뷰 | %s]\n%s".formatted(fmt(m.writtenAt()), m.content()));
        }
        return String.join("\n\n", blocks);
    }

    private String fmt(OffsetDateTime dt) {
        return dt == null ? "날짜미상" : dt.format(DATE);
    }
}
