package com.hinsight.hottrend;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 초실시간 급상승 랭킹(Hot Trend) — 스피드 레이어.
 * <p>Kafka 로 들어오는 조회/구매 이벤트를 Redis Sorted Set 에 <b>분(minute) 버킷</b>으로 누적하고,
 * 조회 시 최근 60개 버킷을 {@code ZUNIONSTORE} 로 합쳐 "굴러가는 최근 1시간" TOP N 을 만든다.
 * 오래된 버킷은 TTL(65분)로 자동 증발한다. → DB/S3 미접근, 전부 인메모리.
 * <p>이벤트 도착(수신) 시각 기준으로 버킷을 나누므로(과거 occurredAt 아님) 지금 유입되는 트래픽이 즉시 반영된다.
 */
@Service
@RequiredArgsConstructor
public class HotTrendService {

    private final StringRedisTemplate redis;

    private static final int WINDOW_MIN = 60;                     // 롤링 윈도우 = 최근 60분
    private static final Duration BUCKET_TTL = Duration.ofMinutes(65);
    private static final String NAME_HASH = "hot:name";          // productId -> 표시명 캐시

    private String bucketKey(String kind, long epochMin) {
        return "hot:" + kind + ":" + epochMin;
    }

    /** 이벤트 1건 반영 — 현재 분 버킷의 상품 점수 += weight (조회=1, 판매=수량). */
    public void hit(String kind, long productId, int weight, String name) {
        long min = Instant.now().getEpochSecond() / 60;
        String key = bucketKey(kind, min);
        redis.opsForZSet().incrementScore(key, String.valueOf(productId), weight);
        redis.expire(key, BUCKET_TTL);
        if (name != null && !name.isBlank()) {
            redis.opsForHash().put(NAME_HASH, String.valueOf(productId), name);
        }
    }

    /** 최근 60분 급상승 TOP N (상품ID·이름·점수). Redis 만 사용. */
    public List<Map<String, Object>> top(String kind, int n) {
        long min = Instant.now().getEpochSecond() / 60;
        List<String> keys = new ArrayList<>(WINDOW_MIN);
        for (int i = 0; i < WINDOW_MIN; i++) {
            keys.add(bucketKey(kind, min - i));
        }
        String dest = "hot:" + kind + ":win";
        // 최근 60개 분 버킷 합산(없는 버킷은 빈 집합으로 처리) → 롤링 1시간 랭킹
        redis.opsForZSet().unionAndStore(keys.get(0), keys.subList(1, keys.size()), dest);
        redis.expire(dest, Duration.ofSeconds(30));

        Set<ZSetOperations.TypedTuple<String>> top =
                redis.opsForZSet().reverseRangeWithScores(dest, 0, n - 1);
        List<Map<String, Object>> out = new ArrayList<>();
        if (top == null) {
            return out;
        }
        int rank = 1;
        for (ZSetOperations.TypedTuple<String> t : top) {
            String pid = t.getValue();
            Object name = redis.opsForHash().get(NAME_HASH, pid);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", rank++);
            row.put("productId", Long.parseLong(pid));
            row.put("name", name != null ? name.toString() : ("상품 #" + pid));
            row.put("count", t.getScore() == null ? 0L : t.getScore().longValue());
            out.add(row);
        }
        return out;
    }
}
