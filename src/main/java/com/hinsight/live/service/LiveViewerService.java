package com.hinsight.live.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveViewerService {

    private final StringRedisTemplate redisTemplate;

    public long enter(Long liveSessionId) {
        try {
            Long count = redisTemplate.opsForValue().increment(viewerKey(liveSessionId));
            return count == null ? 0L : count;
        } catch (RuntimeException e) {
            log.warn("[LIVE] viewer enter failed. liveSessionId={}, message={}", liveSessionId, e.getMessage());
            return 0L;
        }
    }

    public long leave(Long liveSessionId) {
        try {
            Long count = redisTemplate.opsForValue().decrement(viewerKey(liveSessionId));
            if (count == null || count < 0) {
                redisTemplate.opsForValue().set(viewerKey(liveSessionId), "0");
                return 0L;
            }
            return count;
        } catch (RuntimeException e) {
            log.warn("[LIVE] viewer leave failed. liveSessionId={}, message={}", liveSessionId, e.getMessage());
            return 0L;
        }
    }

    public long count(Long liveSessionId) {
        try {
            String value = redisTemplate.opsForValue().get(viewerKey(liveSessionId));
            return value == null ? 0L : Long.parseLong(value);
        } catch (RuntimeException e) {
            log.warn("[LIVE] viewer count failed. liveSessionId={}, message={}", liveSessionId, e.getMessage());
            return 0L;
        }
    }

    private String viewerKey(Long liveSessionId) {
        return "live:session:" + liveSessionId + ":viewers";
    }
}
