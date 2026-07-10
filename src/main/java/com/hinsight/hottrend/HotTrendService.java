package com.hinsight.hottrend;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
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
 * <p>Kafka 로 들어오는 조회/구매 이벤트를 Redis Sorted Set 에 <b>분(minute) 버킷</b>으로 누적한다.
 * 최근 60개 버킷을 합쳐 "굴러가는 최근 1시간" 랭킹을 만드는데, 이 합집합({@code ZUNIONSTORE})은
 * <b>요청마다가 아니라 스케줄로 10초마다 한 번만</b> 미리 계산해 {@code hot:{kind}:win} 에 저장한다.
 * 위젯 폴링(요청 경로)은 미리 계산된 윈도우를 <b>읽기만</b>(O(log N + n)) 한다.
 * <p>이유: 합집합은 O(전체 원소 수) 비용이고 Redis 는 싱글 스레드다. 이걸 폴링 요청마다 돌리면
 * 동시 요청들이 같은 dest 키에 ZUNIONSTORE 를 직렬로 밀어넣어 서로 뒤에서 대기하다 command timeout 이
 * 터진다(thundering herd). 미리 계산 방식은 트래픽과 무관하게 10초당 딱 2번(view/sale)으로 고정된다.
 * <p>오래된 버킷은 TTL(65분)로 자동 증발한다. → DB/S3 미접근, 전부 인메모리.
 */
@Service
@RequiredArgsConstructor
public class HotTrendService {

    private final StringRedisTemplate redis;

    private static final int WINDOW_MIN = 60;                     // 롤링 윈도우 = 최근 60분
    private static final Duration BUCKET_TTL = Duration.ofMinutes(65);
    private static final String NAME_HASH = "hot:name";          // productId -> 표시명 캐시
    private static final int KEEP_TOP = 200;                      // 미리계산 윈도우를 상위 N개로 트림(다음 조회 비용 제한)
    private static final List<String> KINDS = List.of("view", "sale");

    private String bucketKey(String kind, long epochMin) {
        return "hot:" + kind + ":" + epochMin;
    }

    private String winKey(String kind) {
        return "hot:" + kind + ":win";
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

    /**
     * 롤링 윈도우를 10초마다 미리 계산한다. 요청 경로(top)에서 ZUNIONSTORE 를 제거하기 위함.
     * 합집합 결과는 초 단위로 거의 안 바뀌므로 이 정도 주기로 충분하다.
     */
    @Scheduled(fixedDelay = 10_000)
    public void refreshWindows() {
        long min = Instant.now().getEpochSecond() / 60;
        for (String kind : KINDS) {
            List<String> keys = new ArrayList<>(WINDOW_MIN);
            for (int i = 0; i < WINDOW_MIN; i++) {
                keys.add(bucketKey(kind, min - i));
            }
            String dest = winKey(kind);
            // 최근 60개 분 버킷 합산(없는 버킷은 빈 집합으로 처리) → 롤링 1시간 랭킹
            redis.opsForZSet().unionAndStore(keys.get(0), keys.subList(1, keys.size()), dest);
            // 상위 KEEP_TOP 개만 남기고 하위 제거 (윈도우 카디널리티 상한 → 조회/다음 계산 비용 제한)
            redis.opsForZSet().removeRange(dest, 0, -(KEEP_TOP + 1));
            redis.expire(dest, Duration.ofMinutes(2));
        }
    }

    /** 요청 경로: 미리 계산된 윈도우에서 읽기만 (O(log N + n)). ZUNIONSTORE 하지 않는다. */
    public List<Map<String, Object>> top(String kind, int n) {
        Set<ZSetOperations.TypedTuple<String>> top =
                redis.opsForZSet().reverseRangeWithScores(winKey(kind), 0, n - 1);
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
