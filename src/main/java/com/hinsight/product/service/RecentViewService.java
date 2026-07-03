package com.hinsight.product.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecentViewService {

    private static final String KEY_PREFIX = "recent:view:";
    private static final int MAX_SIZE = 10;
    private static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;

    // 상품 상세 진입 기록. 같은 상품을 다시 보면 맨 앞으로
    public void add(Long userId, Long productId) {
        if (userId == null || productId == null) {
            return;
        }
        String key = KEY_PREFIX + userId;
        String value = String.valueOf(productId);
        try {
            ListOperations<String, String> ops = redisTemplate.opsForList();
            ops.remove(key, 0, value);       // 기존 항목 제거(중복 방지)
            ops.leftPush(key, value);        // 맨 앞에 추가(최근순)
            ops.trim(key, 0, MAX_SIZE - 1);  // 최근 N개만 유지
            redisTemplate.expire(key, TTL);
        } catch (Exception e) {
            log.warn("[recent-view] 저장 실패 userId={} productId={}: {}", userId, productId, e.getMessage());
        }
    }

    // 최근 본 상품 ID 목록(최근순). 없거나 실패 시 빈 리스트.
    public List<Long> getRecentProductIds(Long userId) {
        if (userId == null) {
            return List.of();
        }
        try {
            List<String> ids = redisTemplate.opsForList().range(KEY_PREFIX + userId, 0, MAX_SIZE - 1);
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            return ids.stream().map(Long::valueOf).toList();
        } catch (Exception e) {
            log.warn("[recent-view] 조회 실패 userId={}: {}", userId, e.getMessage());
            return List.of();
        }
    }
}
