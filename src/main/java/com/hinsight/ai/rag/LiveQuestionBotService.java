package com.hinsight.ai.rag;

import com.hinsight.ai.embedding.EmbeddingService;
import com.hinsight.ai.rag.guard.QuestionGuard;
import com.hinsight.live.dao.LiveSessionDao;
import com.hinsight.live.model.dto.LiveChatMessage;
import com.hinsight.live.model.vo.LiveSession;
import com.hinsight.live.service.LiveChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.stream.Collectors;

/**
 * 라이브 채팅에서 자주 올라오는 질문을 탐지(catch)해 리뷰 기반 답변을 자동으로 채팅에 쏘는 봇.
 *
 * <p>흐름: 시청자 메시지 → 질문 여부 판별 → BGE-M3 임베딩 →
 * {@link SemanticQuestionAggregator}로 <b>의미가 비슷한 질문끼리 군집화</b>해 빈도 집계 →
 * 한 군집이 짧은 시간 내 {@code threshold}회 이상이면 트리거(쿨다운 1회) →
 * {@link RagService}로 답변 생성 → {@code /topic/live/{id}}로 봇 메시지 브로드캐스트.</p>
 *
 * <p>문구가 달라도("색상 어두운가요"/"옷색 어떰") 같은 질문으로 묶인다.
 * STOMP 수신 스레드를 막지 않도록 {@link Async}로 별도 스레드에서 처리하며, 어떤 실패도 채팅 흐름을 끊지 않게 삼킨다.</p>
 */
@Service
public class LiveQuestionBotService {

    private static final Logger log = LoggerFactory.getLogger(LiveQuestionBotService.class);

    private final EmbeddingService embeddingService;
    private final QuestionGuard questionGuard;
    private final SemanticQuestionAggregator aggregator;
    private final LiveSessionDao liveSessionDao;
    private final RagService ragService;
    private final SimpMessagingTemplate messaging;
    private final LiveChatService liveChatService;

    @Value("${rag.bot.enabled:true}")              private boolean enabled;
    @Value("${rag.bot.threshold:3}")               private int threshold;        // 트리거 최소 반복 횟수
    @Value("${rag.bot.window-seconds:120}")        private long windowSeconds;   // 군집 유지 창
    @Value("${rag.bot.cooldown-seconds:300}")      private long cooldownSeconds; // 같은 군집 재답변 방지
    @Value("${rag.bot.similarity-threshold:0.75}") private double simThreshold;  // 같은 질문으로 볼 코사인 유사도
    @Value("${rag.bot.answer-timeout-seconds:30}") private long answerTimeoutSeconds;

    public LiveQuestionBotService(EmbeddingService embeddingService,
                                  QuestionGuard questionGuard,
                                  SemanticQuestionAggregator aggregator,
                                  LiveSessionDao liveSessionDao,
                                  RagService ragService,
                                  SimpMessagingTemplate messaging,
                                  LiveChatService liveChatService) {
        this.embeddingService = embeddingService;
        this.questionGuard = questionGuard;
        this.aggregator = aggregator;
        this.liveSessionDao = liveSessionDao;
        this.ragService = ragService;
        this.messaging = messaging;
        this.liveChatService = liveChatService;
    }

    /** 시청자 메시지 1건 처리. STOMP 스레드에서 호출되지만 즉시 별도 스레드로 넘어간다. */
    @Async
    public void onUserMessage(Long liveSessionId, String rawMessage) {
        if (!enabled || liveSessionId == null || rawMessage == null) return;
        try {
            String q = rawMessage.strip();

            // 가드레일: 집계/임베딩/LLM 이전에 비질문·비방/트롤링을 즉시 파기 (Requirement 2)
            QuestionGuard.Decision decision = questionGuard.classify(q);
            if (decision != QuestionGuard.Decision.ACCEPT) {
                log.info("[리뷰봇] 가드 파기({}) liveSessionId={}, msg='{}'", decision, liveSessionId, rawMessage);
                return;
            }

            log.info("[리뷰봇] 질문 수신 liveSessionId={}, q='{}' (임베딩 시작)", liveSessionId, q);
            float[] vec = embeddingService.embed(q);   // 의미 군집화를 위해 질문 임베딩
            SemanticQuestionAggregator.Result r = aggregator.record(
                    liveSessionId, q, vec, threshold, simThreshold,
                    windowSeconds * 1000L, cooldownSeconds * 1000L);

            log.info("[리뷰봇] 질문 감지 liveSessionId={}, q='{}', 유사군 {}/{} (sim={})",
                    liveSessionId, q, r.count(), threshold, String.format("%.2f", r.similarity()));

            if (!r.triggered()) return;

            log.info("[리뷰봇] 트리거! liveSessionId={}, 대표질문='{}'", liveSessionId, r.representativeQuestion());
            answerAndBroadcast(liveSessionId, r.representativeQuestion());
        } catch (Exception e) {
            log.warn("[리뷰봇] 처리 실패 liveSessionId=" + liveSessionId, e);   // 스택트레이스까지
        }
    }

    private void answerAndBroadcast(Long liveSessionId, String question) {
        LiveSession session = liveSessionDao.findById(liveSessionId);
        if (session == null || session.getProductId() == null) {
            log.warn("[리뷰봇] 세션→상품 매핑 없음. liveSessionId={}", liveSessionId);
            return;
        }
        long productId = session.getProductId();

        OffsetDateTime cutoff = ragService.resolveCutoff(productId, null);
        log.info("[리뷰봇] 답변 생성 시작 productId={}, cutoff={}", productId, cutoff);
        String answer = ragService.answer(productId, question, cutoff)
                .collect(Collectors.joining())
                .block(Duration.ofSeconds(answerTimeoutSeconds));
        log.info("[리뷰봇] 답변 생성 완료 len={}", answer == null ? 0 : answer.length());

        // Silent Drop: LLM 이 IGNORE(무응답)를 냈거나 빈 답이면 채팅에 아무것도 보내지 않고 조용히 종료 (Requirement 3)
        // → "정보가 없습니다" 공지로 방송 흐름을 끊지 않는다. IGNORE 는 RagService 에서 캐시에도 적재되지 않는다.
        if (RagService.isNoAnswer(answer)) {
            log.info("[리뷰봇] Silent Drop (IGNORE/무응답) liveSessionId={}, productId={}, q='{}'",
                    liveSessionId, productId, question);
            return;
        }

        LiveChatMessage botMsg = LiveChatMessage.bot(question, answer.strip());
        messaging.convertAndSend("/topic/live/" + liveSessionId, botMsg);
        liveChatService.saveBotAnswer(liveSessionId, botMsg);   // 전용 로그에 적재(공지 보드 과거기록용)
        log.info("[리뷰봇] 답변 전송 liveSessionId={}, productId={}, q='{}'", liveSessionId, productId, question);
    }
}
