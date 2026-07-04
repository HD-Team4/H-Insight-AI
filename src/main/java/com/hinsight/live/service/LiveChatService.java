package com.hinsight.live.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hinsight.live.model.dto.LiveChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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

    private String chatKey(Long liveSessionId) {
        return "live:session:" + liveSessionId + ":chat";
    }
}
