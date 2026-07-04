package com.hinsight.ai.rag;

import com.hinsight.live.dao.LiveSessionDao;
import com.hinsight.live.model.dto.LiveChatMessage;
import com.hinsight.live.model.vo.LiveSession;
import com.hinsight.live.service.LiveChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 라이브 채팅에서 자주 올라오는 질문을 탐지(catch)해 리뷰 기반 답변을 자동으로 채팅에 쏘는 봇.
 *
 * <p>흐름: 시청자 메시지 → 질문 여부 판별 → 정규화 후 Redis 로 빈도 카운트 →
 * 짧은 시간 내 {@code threshold}회 이상이면 트리거(쿨다운 1회) →
 * {@link RagService} 로 답변 생성 → {@code /topic/live/{id}} 로 봇 메시지 브로드캐스트.</p>
 *
 * <p>STOMP 수신 스레드를 막지 않도록 {@link Async} 로 별도 스레드에서 처리하며,
 * 어떤 실패도 채팅 흐름을 끊지 않게 전부 삼켜서 로깅한다.</p>
 */
@Service
public class LiveQuestionBotService {

    private static final Logger log = LoggerFactory.getLogger(LiveQuestionBotService.class);

    // 한국어 의문 힌트 (물음표가 없어도 질문으로 간주)
    private static final List<String> QUESTION_HINTS = List.of(
            "어때", "어떤", "어떠", "나요", "까요", "얼마", "있나", "없나", "되나", "될까",
            "인가", "궁금", "어디", "언제", "몇", "가요", "니까", "은지", "는지");

    private final StringRedisTemplate redis;
    private final LiveSessionDao liveSessionDao;
    private final RagService ragService;
    private final SimpMessagingTemplate messaging;
    private final LiveChatService liveChatService;

    @Value("${rag.bot.enabled:true}")          private boolean enabled;
    @Value("${rag.bot.threshold:3}")           private int threshold;       // 트리거 최소 반복 횟수
    @Value("${rag.bot.window-seconds:120}")    private long windowSeconds;  // 빈도 집계 창
    @Value("${rag.bot.cooldown-seconds:300}")  private long cooldownSeconds;// 같은 질문 재답변 방지
    @Value("${rag.bot.answer-timeout-seconds:30}") private long answerTimeoutSeconds;

    public LiveQuestionBotService(StringRedisTemplate redis,
                                  LiveSessionDao liveSessionDao,
                                  RagService ragService,
                                  SimpMessagingTemplate messaging,
                                  LiveChatService liveChatService) {
        this.redis = redis;
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
            String norm = normalize(rawMessage);
            if (norm.length() < 2 || !looksLikeQuestion(rawMessage)) return;

            int hash = norm.hashCode();
            String countKey = "live:" + liveSessionId + ":q:" + hash;

            Long count = redis.opsForValue().increment(countKey);
            if (count != null && count == 1L) {
                redis.expire(countKey, Duration.ofSeconds(windowSeconds));
            }
            log.info("[리뷰봇] 질문 감지 liveSessionId={}, q='{}', count={}/{}", liveSessionId, norm, count, threshold);
            if (count == null || count < threshold) return;

            // 쿨다운 선점(원자적): 처음 성공한 호출만 실제로 답변한다.
            String cooldownKey = "live:" + liveSessionId + ":qcool:" + hash;
            Boolean won = redis.opsForValue()
                    .setIfAbsent(cooldownKey, "1", Duration.ofSeconds(cooldownSeconds));
            if (!Boolean.TRUE.equals(won)) return;

            log.info("[리뷰봇] 트리거! liveSessionId={}, q='{}'", liveSessionId, rawMessage.strip());
            answerAndBroadcast(liveSessionId, rawMessage.strip());
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

        if (answer == null || answer.isBlank()) return;

        LiveChatMessage botMsg = LiveChatMessage.bot(answer.strip());
        messaging.convertAndSend("/topic/live/" + liveSessionId, botMsg);
        liveChatService.save(liveSessionId, botMsg);   // 늦게 입장한 시청자도 replay 로 보게
        log.info("[리뷰봇] 답변 전송 liveSessionId={}, productId={}, q='{}'", liveSessionId, productId, question);
    }

    private boolean looksLikeQuestion(String s) {
        if (s.contains("?") || s.contains("？")) return true;
        return QUESTION_HINTS.stream().anyMatch(s::contains);
    }

    private String normalize(String s) {
        return s.strip().toLowerCase().replaceAll("[\\s?!.~。？！]+", "");
    }
}
