package com.hinsight.waitingroom.service;

import com.hinsight.waitingroom.config.WaitingRoomRedisConfig;
import com.hinsight.waitingroom.dto.WaitingStatusResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 가상 대기열 — Redis 번호표(deli-counter) 방식.
 *
 * <p>입장하려는 사람은 {@code seq} 를 INCR 해 티켓 번호를 받는다.
 * 스케줄러가 {@code serving}(현재까지 호출된 번호)을 주기적으로 전진시키고,
 * 자기 티켓이 {@code serving} 이하가 되면 입장(READY)한다.
 * serving 은 발급된 티켓 수를 넘지 않게 클램프하므로, 한산할 때 접속해도 최소 한 틱은 대기해
 * 대기 페이지가 항상 노출된다(부하가 몰리면 실제 순번대로 줄이 선다).
 *
 * <p>인메모리 타이머 방식과 달리 상태가 Redis(전용 ElastiCache)에 있어 인스턴스 재시작·다중 인스턴스에도 순번이 공유된다.
 * Redis 장애 시에는 사이트 전체를 막지 않도록 <b>fail-open</b>(그냥 입장)으로 동작한다.
 */
@Slf4j
@Service
public class WaitingRoomService {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> releaseScript;

    private final String seqKey;
    private final String servingKey;
    private final String tokenKeyPrefix;
    private final Duration tokenTtl;

    /** 한 틱(release-interval)마다 입장시킬 인원 */
    private final int releaseCount;
    /** serving 전진 주기(초) — eta 계산용 */
    private final double releaseIntervalSec;

    /** 토큰과 그 티켓 번호 묶음 */
    private record Ticket(String token, long number) {}

    public WaitingRoomService(
            @Qualifier(WaitingRoomRedisConfig.WR_TEMPLATE) StringRedisTemplate redis,
            @Value("${waiting-room.queue.key-prefix:wr:}") String keyPrefix,
            @Value("${waiting-room.queue.token-ttl-minutes:30}") long tokenTtlMinutes,
            @Value("${waiting-room.queue.release-count:5}") int releaseCount,
            @Value("${waiting-room.queue.release-interval-ms:3000}") long releaseIntervalMs) {
        this.redis = redis;
        this.seqKey = keyPrefix + "seq";
        this.servingKey = keyPrefix + "serving";
        this.tokenKeyPrefix = keyPrefix + "tok:";
        this.tokenTtl = Duration.ofMinutes(tokenTtlMinutes);
        this.releaseCount = Math.max(1, releaseCount);
        this.releaseIntervalSec = Math.max(1, releaseIntervalMs) / 1000.0;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/waiting-room-release.lua"));
        script.setResultType(Long.class);
        this.releaseScript = script;
    }

    /**
     * 대기 상태 조회. 토큰이 없거나 만료됐으면 새 티켓을 발급해 대기열에 넣는다.
     * Redis 장애 시 fail-open(READY)로 응답해 사용자가 갇히지 않게 한다.
     */
    public WaitingStatusResponse status(String token) {
        try {
            Ticket ticket = resolveTicket(token);
            long serving = readLong(servingKey);
            if (ticket.number() <= serving) {
                return new WaitingStatusResponse(
                        WaitingStatusResponse.STATUS_READY, 1, 0, 0, ticket.token());
            }
            long position = ticket.number() - serving;     // 내 대기 순번(1 = 바로 다음)
            long ahead = position - 1;                     // 앞에 남은 인원
            long etaSeconds = (long) Math.ceil((double) position / releaseCount * releaseIntervalSec);
            return new WaitingStatusResponse(
                    WaitingStatusResponse.STATUS_WAITING, position, ahead, etaSeconds, ticket.token());
        } catch (Exception e) {
            log.warn("[waiting-room] status 실패 → fail-open(READY): {}", e.getMessage());
            return new WaitingStatusResponse(WaitingStatusResponse.STATUS_READY, 1, 0, 0,
                    (token == null || token.isBlank()) ? UUID.randomUUID().toString() : token);
        }
    }

    /** 입장 검증 — 티켓이 serving 이하면 1회용으로 소모하고 통과. Redis 장애 시 fail-open(true). */
    public boolean admit(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            String raw = redis.opsForValue().get(tokenKeyPrefix + token);
            if (raw == null) {
                return false;
            }
            long ticket = Long.parseLong(raw);
            if (ticket <= readLong(servingKey)) {
                redis.delete(tokenKeyPrefix + token);   // 재사용 방지(1회용)
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("[waiting-room] admit 실패 → fail-open(통과): {}", e.getMessage());
            return true;
        }
    }

    /** serving 포인터를 주기적으로 전진(발급 수 이하로 클램프). 다중 인스턴스면 조금 더 빠르게 빠지지만 안전. */
    @Scheduled(fixedDelayString = "${waiting-room.queue.release-interval-ms:3000}")
    void releaseTick() {
        try {
            redis.execute(releaseScript, List.of(seqKey, servingKey), String.valueOf(releaseCount));
        } catch (Exception e) {
            log.warn("[waiting-room] release 스케줄 실패: {}", e.getMessage());
        }
    }

    /** 토큰→티켓. 새/만료 토큰이면 seq INCR 로 신규 발급한다(공유 상태 없이 결과를 즉시 반환). */
    private Ticket resolveTicket(String token) {
        if (token != null && !token.isBlank()) {
            String raw = redis.opsForValue().get(tokenKeyPrefix + token);
            if (raw != null) {
                return new Ticket(token, Long.parseLong(raw));
            }
        }
        String newToken = UUID.randomUUID().toString();
        Long ticket = redis.opsForValue().increment(seqKey);   // 원자적 티켓 발급
        long number = (ticket == null) ? 1L : ticket;
        redis.opsForValue().set(tokenKeyPrefix + newToken, Long.toString(number), tokenTtl);
        return new Ticket(newToken, number);
    }

    private long readLong(String key) {
        String v = redis.opsForValue().get(key);
        return (v == null) ? 0L : Long.parseLong(v);
    }
}
