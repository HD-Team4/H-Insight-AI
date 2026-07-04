package com.hinsight.live.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hinsight.live.model.dto.LiveChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 라이브 채팅 히스토리 저장소.
 * 최근 {@value #MAX_HISTORY}개만 Redis 리스트에 남겨(LPUSH + LTRIM),
 * 늦게 입장한 시청자에게 스크롤백(replay)으로 내려준다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveChatService {

    private static final int MAX_HISTORY = 50;
    private static final Duration GUEST_NAME_TTL = Duration.ofHours(12);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 브로드캐스트한 메시지를 히스토리에 적재한다. Redis 장애 시에도 채팅 흐름은 끊기지 않도록 삼켜서 로깅만 한다. */
    public void save(Long liveSessionId, LiveChatMessage message) {
        try {
            String key = chatKey(liveSessionId);
            redisTemplate.opsForList().leftPush(key, objectMapper.writeValueAsString(message));
            redisTemplate.opsForList().trim(key, 0, MAX_HISTORY - 1);
        } catch (Exception e) {
            log.warn("[LIVE] chat save failed. liveSessionId={}, message={}", liveSessionId, e.getMessage());
        }
    }

    // 최근 메시지를 오래된 것 → 최신 순으로 반환
    public List<LiveChatMessage> recent(Long liveSessionId) {
        try {
            List<String> raw = redisTemplate.opsForList().range(chatKey(liveSessionId), 0, MAX_HISTORY - 1);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<LiveChatMessage> messages = new ArrayList<>(raw.size());
            for (String json : raw) {
                messages.add(objectMapper.readValue(json, LiveChatMessage.class));
            }
            Collections.reverse(messages); // LPUSH 저장이라 최신이 앞 → 뒤집어 오래된 순으로
            return messages;
        } catch (Exception e) {
            log.warn("[LIVE] chat history load failed. liveSessionId={}, message={}", liveSessionId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 익명 시청자의 방별 표시명을 배정한다.
     * 같은 senderId(연결)면 항상 같은 이름을, 방 안에서 서로 다른 게스트는 순번으로 서로 다른 이름을 받는다.
     * → "게스트1", "게스트2", ... 로 겹치지 X
     */
    public String resolveGuestName(Long liveSessionId, String senderId) {
        try {
            String nameKey = guestNameKey(liveSessionId, senderId);
            String existing = redisTemplate.opsForValue().get(nameKey);
            if (existing != null) {
                return existing;
            }
            Long seq = redisTemplate.opsForValue().increment(guestSeqKey(liveSessionId)); // 원자적 순번
            String name = "게스트 " + (seq == null ? "" : seq);
            // 동시에 같은 senderId 가 두 번 들어오는 경쟁을 대비: 먼저 넣은 값이 이기고 여분 순번은 버린다.
            Boolean set = redisTemplate.opsForValue().setIfAbsent(nameKey, name, GUEST_NAME_TTL);
            if (Boolean.FALSE.equals(set)) {
                String winner = redisTemplate.opsForValue().get(nameKey);
                return winner == null ? name : winner;
            }
            return name;
        } catch (RuntimeException e) {
            log.warn("[LIVE] guest name resolve failed. liveSessionId={}, message={}", liveSessionId, e.getMessage());
            return "게스트";
        }
    }

    private String chatKey(Long liveSessionId) {
        return "live:session:" + liveSessionId + ":chat";
    }

    private String guestSeqKey(Long liveSessionId) {
        return "live:session:" + liveSessionId + ":guestSeq";
    }

    private String guestNameKey(Long liveSessionId, String senderId) {
        return "live:session:" + liveSessionId + ":guestName:" + senderId;
    }
}
