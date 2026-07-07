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

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 가상 대기열 — 용량 기반 게이트 + Redis 번호표 + AIMD 자기조정 방출.
 *
 * <p><b>입장 모델</b>: 평상시(CPU 여유 &amp; 대기 줄 없음)에는 접속을 <b>즉시 통과</b>시킨다(대기 페이지 안 뜸).
 * 접속이 몰려 인스턴스 CPU 가 cpu-target(기본 80%)에 닿으면, 그 시점부터 <b>새 유입만 번호표</b>를 받아 대기열에 선다.
 * 이미 들어간 사용자는 인터셉터의 세션 통과권으로 사이트 전체를 대기 없이 계속 이용한다.
 *
 * <p><b>대기열 드레인 속도(AIMD)</b>: serving(입장 처리된 번호)을 전진시키는 초당 인원을
 * TCP 혼잡제어식 가감산으로 자기조정한다 — 매 틱 CPU&lt;target 이면 rate 를 가산 증가(+aimd-increase),
 * CPU&ge;target 이면 승산 감소(×aimd-decrease). rate 는 [rate-min, rate-max] 로 클램프된다.
 * 그래서 방출 속도가 CPU 목표 부근으로 스스로 수렴하며, rate-min(35)·rate-max(200)은 고정 속도가 아니라
 * 자기조정의 하한/상한일 뿐이다.
 *
 * <p>상태는 전용 Redis(ElastiCache)에 있어 재시작에도 순번이 유지된다. Redis 장애 시엔
 * 사이트를 막지 않도록 <b>fail-open</b>으로 동작한다.
 * (다중 인스턴스 확장 시 releaseTick·rate 상태는 리더 1대 또는 공유 저장소로 옮겨야 한다.)
 */
@Slf4j
@Service
public class WaitingRoomService {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> releaseScript;
    private final com.sun.management.OperatingSystemMXBean osBean;

    private final String seqKey;
    private final String servingKey;
    private final String tokenKeyPrefix;
    private final Duration tokenTtl;

    /** 이 부하(0~1) 이상이면 새 유입을 대기시키고, AIMD 는 방출을 줄인다 */
    private final double cpuTarget;
    private final double rateMax;         // 방출 레이트 상한(/s)
    private final double rateMin;         // 방출 레이트 하한(/s)
    private final double aimdIncrease;    // CPU 여유 시 매 틱 가산 증가(/s)
    private final double aimdDecrease;    // CPU 포화 시 승산 감소 계수
    private final long releaseIntervalMs;

    private volatile double currentRate;  // 현재 방출 레이트(/s) — AIMD 로 변동
    private volatile double lastCpu = 0.0; // 최근 CPU 판독치 — 게이트의 직접입장 판정에 사용

    /** 토큰과 그 티켓 번호 묶음 */
    private record Ticket(String token, long number) {}

    public WaitingRoomService(
            @Qualifier(WaitingRoomRedisConfig.WR_TEMPLATE) StringRedisTemplate redis,
            @Value("${waiting-room.queue.key-prefix:wr:}") String keyPrefix,
            @Value("${waiting-room.queue.token-ttl-minutes:30}") long tokenTtlMinutes,
            @Value("${waiting-room.queue.cpu-target:0.80}") double cpuTarget,
            @Value("${waiting-room.queue.rate-max:200}") double rateMax,
            @Value("${waiting-room.queue.rate-min:35}") double rateMin,
            @Value("${waiting-room.queue.rate-initial:35}") double rateInitial,
            @Value("${waiting-room.queue.aimd-increase:20}") double aimdIncrease,
            @Value("${waiting-room.queue.aimd-decrease:0.5}") double aimdDecrease,
            @Value("${waiting-room.queue.release-interval-ms:1000}") long releaseIntervalMs) {
        this.redis = redis;
        this.seqKey = keyPrefix + "seq";
        this.servingKey = keyPrefix + "serving";
        this.tokenKeyPrefix = keyPrefix + "tok:";
        this.tokenTtl = Duration.ofMinutes(tokenTtlMinutes);
        this.cpuTarget = cpuTarget;
        this.rateMax = Math.max(1, rateMax);
        this.rateMin = Math.max(1, Math.min(rateMin, this.rateMax));
        this.aimdIncrease = Math.max(0.1, aimdIncrease);
        this.aimdDecrease = Math.min(0.99, Math.max(0.1, aimdDecrease));
        this.releaseIntervalMs = Math.max(200, releaseIntervalMs);
        this.currentRate = Math.max(this.rateMin, Math.min(rateInitial, this.rateMax));
        this.osBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/waiting-room-release.lua"));
        script.setResultType(Long.class);
        this.releaseScript = script;
    }

    /**
     * 지금 새 접속을 대기 없이 바로 통과시켜도 되는가.
     * CPU 여유(&lt;target)가 있고 대기 줄이 없을 때만 true(직접 입장).
     * 대기 줄이 이미 있으면 공정성을 위해 새 유입도 줄을 서야 하므로 false.
     */
    public boolean canAdmitDirectly() {
        if (lastCpu >= cpuTarget) {
            return false;                         // 포화 → 번호표
        }
        try {
            return readLong(seqKey) <= readLong(servingKey);   // backlog 없음 → 직접 입장
        } catch (Exception e) {
            log.warn("[waiting-room] canAdmitDirectly 실패 → fail-open(직접입장): {}", e.getMessage());
            return true;
        }
    }

    /**
     * 대기 상태 조회(대기 페이지의 폴링). 토큰이 없거나 만료됐으면 새 티켓을 발급해 대기열에 넣는다.
     * Redis 장애 시 fail-open(READY).
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
            long etaSeconds = (long) Math.ceil(position / Math.max(1.0, currentRate));  // 현재 방출 레이트 기준
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
                redis.delete(tokenKeyPrefix + token);   // 재사용 방지(1회용) — 입장 후엔 세션 통과권이 접근을 담당
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("[waiting-room] admit 실패 → fail-open(통과): {}", e.getMessage());
            return true;
        }
    }

    /**
     * AIMD 로 방출 레이트를 조정하고 serving 을 전진시킨다.
     * CPU&lt;target → 가산 증가(여유), CPU&ge;target·판독불가 → 승산 감소(포화). [rate-min, rate-max] 클램프.
     */
    @Scheduled(fixedDelayString = "${waiting-room.queue.release-interval-ms:1000}")
    void releaseTick() {
        try {
            double cpu = osBean.getCpuLoad();                  // 시스템 전체 CPU(0~1), 판독 불가 시 음수/NaN
            boolean known = !(Double.isNaN(cpu) || cpu < 0);
            if (known) {
                lastCpu = cpu;
            }
            double effectiveCpu = known ? cpu : cpuTarget;     // 못 읽으면 보수적으로 포화 취급(감소)

            if (effectiveCpu < cpuTarget) {
                currentRate = Math.min(rateMax, currentRate + aimdIncrease);   // 가산 증가
            } else {
                currentRate = Math.max(rateMin, currentRate * aimdDecrease);   // 승산 감소
            }

            long batch = Math.max(1, Math.round(currentRate * (releaseIntervalMs / 1000.0)));
            Long serving = redis.execute(releaseScript, List.of(seqKey, servingKey), Long.toString(batch));
            if (log.isDebugEnabled()) {
                log.debug("[waiting-room] cpu={} rate={}/s batch={} serving={}",
                        String.format("%.2f", cpu), String.format("%.0f", currentRate), batch, serving);
            }
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
