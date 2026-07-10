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

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 가상 대기열 — 동시성(in-flight) 기반 용량 게이트 + Redis 번호표 + AIMD 자기조정 방출.
 *
 * <p><b>판정 신호는 동시 처리 중 요청 수(inFlight)</b>다. 이 앱은 부하가 와도 CPU 는 낮고(DB I/O 대기)
 * 실제 병목은 DB 커넥션 풀이라, "지금 게이트를 통과해 처리 중인 /customer/** 요청 수"가 포화의 직접 신호다.
 * inFlight &lt; max-concurrent 이면 여유로 보고, 그 이상이면 포화로 본다(부차적으로 CPU 상한도 함께 본다).
 *
 * <p><b>입장 모델</b>: 여유(inFlight&lt;max & 대기 줄 없음)면 즉시 통과, 포화면 새 유입만 번호표를 받아 대기.
 * 입장자는 인터셉터의 세션 통과권으로 사이트 전체를 이용한다(그 요청들도 inFlight 로 집계돼 게이트에 반영).
 *
 * <p><b>드레인(AIMD)</b>: serving 전진 속도를 매 틱 여유면 가산 증가, 포화면 승산 감소로 자기조정한다
 * ([rate-min, rate-max] 클램프). 상태는 전용 Redis(ElastiCache)에 있고 Redis 장애 시 fail-open.
 */
@Slf4j
@Service
public class WaitingRoomService {

    private final StringRedisTemplate redis;
    private final DataSource dataSource;   // primary(MySQL) 풀 — 테스트용 커넥션 점유에 사용
    private final RedisScript<Long> releaseScript;
    private final com.sun.management.OperatingSystemMXBean osBean;

    private final String seqKey;
    private final String servingKey;
    private final String tokenKeyPrefix;
    private final Duration tokenTtl;

    /** 동시 처리 한도 — 게이트를 통과해 처리 중인 요청이 이 수 이상이면 새 유입은 대기 */
    private final int maxConcurrent;
    /** CPU 부차 상한(0~1). inFlight 여유라도 CPU 가 이 이상이면 포화로 본다 */
    private final double cpuTarget;
    private final double rateMax;
    private final double rateMin;
    private final double aimdIncrease;
    private final double aimdDecrease;
    private final long releaseIntervalMs;
    /** 부하 테스트 전용 — 입장 요청이 DB 커넥션을 붙잡을 시간(ms). 운영 기본 0 = 미동작 */
    private final long testHoldMs;

    private final AtomicInteger inFlight = new AtomicInteger(0);  // 게이트 통과 후 처리 중인 요청 수
    private volatile double currentRate;
    private volatile double lastCpu = 0.0;

    private record Ticket(String token, long number) {}

    public WaitingRoomService(
            @Qualifier(WaitingRoomRedisConfig.WR_TEMPLATE) StringRedisTemplate redis,
            DataSource dataSource,
            @Value("${waiting-room.queue.key-prefix:wr:}") String keyPrefix,
            @Value("${waiting-room.queue.token-ttl-minutes:30}") long tokenTtlMinutes,
            @Value("${waiting-room.queue.max-concurrent:60}") int maxConcurrent,
            @Value("${waiting-room.queue.cpu-target:0.80}") double cpuTarget,
            @Value("${waiting-room.queue.rate-max:200}") double rateMax,
            @Value("${waiting-room.queue.rate-min:35}") double rateMin,
            @Value("${waiting-room.queue.rate-initial:35}") double rateInitial,
            @Value("${waiting-room.queue.aimd-increase:20}") double aimdIncrease,
            @Value("${waiting-room.queue.aimd-decrease:0.5}") double aimdDecrease,
            @Value("${waiting-room.queue.release-interval-ms:1000}") long releaseIntervalMs,
            @Value("${waiting-room.queue.test-hold-ms:0}") long testHoldMs) {
        this.redis = redis;
        this.dataSource = dataSource;
        this.testHoldMs = Math.max(0, testHoldMs);
        this.seqKey = keyPrefix + "seq";
        this.servingKey = keyPrefix + "serving";
        this.tokenKeyPrefix = keyPrefix + "tok:";
        this.tokenTtl = Duration.ofMinutes(tokenTtlMinutes);
        this.maxConcurrent = Math.max(1, maxConcurrent);
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

    /** 이미 입장 확정된(세션 통과권) 요청의 처리 시작 — 무조건 집계(한도 미적용) */
    public void enter() { inFlight.incrementAndGet(); }
    /** 처리 종료 — 인터셉터 afterCompletion 에서 호출 */
    public void exit()  { inFlight.decrementAndGet(); }

    /**
     * 부하 테스트 전용 — 입장한 요청이 primary(MySQL) 풀에서 실제 커넥션을 빌려
     * {@code test-hold-ms} 동안 {@code SELECT SLEEP(...)} 로 붙잡았다 놓는다.
     * "DB 가 동시에 max-concurrent 개만 처리" 상황을 인위 재현해 대기열이 실제로 차게 만든다.
     * 운영에선 test-hold-ms=0 이라 즉시 return(no-op). 실패해도 요청은 그대로 진행(무시).
     */
    public void holdConnectionForTest() {
        if (testHoldMs <= 0) return;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT SLEEP(?)")) {
            ps.setDouble(1, testHoldMs / 1000.0);
            ps.execute();
        } catch (Exception e) {
            log.warn("[waiting-room] test-hold 실패(무시): {}", e.getMessage());
        }
    }

    /**
     * 새 유입을 대기 없이 직접 입장시킬 수 있으면 <b>슬롯을 원자적으로 예약</b>하고 true 반환.
     * (검사-후-증가 사이의 경쟁으로 한도를 넘기지 않도록 incrementAndGet 으로 예약 → 초과면 롤백)
     * 호출측은 이 true 에 대해 enter() 를 다시 부르면 안 된다(이미 +1 됨). 처리 종료 시 exit() 로 -1.
     */
    public boolean tryAdmitDirect() {
        if (lastCpu >= cpuTarget || dbPoolExhausted()) {
            return false;                          // CPU 50%+ 또는 DB 풀 만석 → 대기 페이지로
        }
        try {
            if (readLong(seqKey) > readLong(servingKey)) {
                return false;                      // 대기 줄 있음 → 공정성 위해 줄서기
            }
        } catch (Exception e) {
            log.warn("[waiting-room] backlog 확인 실패 → 원자 예약으로 진행: {}", e.getMessage());
        }
        if (inFlight.incrementAndGet() > maxConcurrent) {  // 원자적 동시성 슬롯 예약
            inFlight.decrementAndGet();
            return false;                          // 만석 → 대기
        }
        return true;
    }

    /** 대기 상태 조회(폴링). 토큰 없거나 만료면 새 티켓 발급. Redis 장애 시 fail-open(READY). */
    public WaitingStatusResponse status(String token) {
        try {
            Ticket ticket = resolveTicket(token);
            long serving = readLong(servingKey);
            if (ticket.number() <= serving) {
                return new WaitingStatusResponse(
                        WaitingStatusResponse.STATUS_READY, 1, 0, 0, ticket.token());
            }
            long position = ticket.number() - serving;
            long ahead = position - 1;
            long etaSeconds = (long) Math.ceil(position / Math.max(1.0, currentRate));
            return new WaitingStatusResponse(
                    WaitingStatusResponse.STATUS_WAITING, position, ahead, etaSeconds, ticket.token());
        } catch (Exception e) {
            log.warn("[waiting-room] status 실패 → fail-open(READY): {}", e.getMessage());
            return new WaitingStatusResponse(WaitingStatusResponse.STATUS_READY, 1, 0, 0,
                    (token == null || token.isBlank()) ? UUID.randomUUID().toString() : token);
        }
    }

    /** 입장 검증 — 티켓이 serving 이하면 1회용 소모하고 통과. Redis 장애 시 fail-open(true). */
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
                redis.delete(tokenKeyPrefix + token);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("[waiting-room] admit 실패 → fail-open(통과): {}", e.getMessage());
            return true;
        }
    }

    /**
     * AIMD 로 방출 레이트 조정 후 serving 전진.
     * 여유(inFlight&lt;max & CPU&lt;target) → 가산 증가, 포화 → 승산 감소. [rate-min, rate-max] 클램프.
     */
    @Scheduled(fixedDelayString = "${waiting-room.queue.release-interval-ms:1000}")
    void releaseTick() {
        try {
            double cpu = osBean.getCpuLoad();
            if (!(Double.isNaN(cpu) || cpu < 0)) {
                lastCpu = cpu;
            }
            boolean saturated = inFlight.get() >= maxConcurrent || lastCpu >= cpuTarget || dbPoolExhausted();
            if (!saturated) {
                currentRate = Math.min(rateMax, currentRate + aimdIncrease);
            } else {
                currentRate = Math.max(rateMin, currentRate * aimdDecrease);
            }

            long batch = Math.max(1, Math.round(currentRate * (releaseIntervalMs / 1000.0)));
            Long serving = redis.execute(releaseScript, List.of(seqKey, servingKey), Long.toString(batch));
            if (log.isDebugEnabled()) {
                log.debug("[waiting-room] inflight={} cpu={} rate={}/s batch={} serving={}",
                        inFlight.get(), String.format("%.2f", lastCpu),
                        String.format("%.0f", currentRate), batch, serving);
            }
        } catch (Exception e) {
            log.warn("[waiting-room] release 스케줄 실패: {}", e.getMessage());
        }
    }

    /** DB 커넥션 풀이 꽉 차 대기자가 생겼는가 — HikariCP 한정 best-effort(판독 불가 시 false). */
    private boolean dbPoolExhausted() {
        try {
            if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hds) {
                var pool = hds.getHikariPoolMXBean();
                return pool != null && pool.getThreadsAwaitingConnection() > 0;
            }
        } catch (Exception ignore) { /* 판독 실패 시 포화 아님으로 간주 */ }
        return false;
    }

    private Ticket resolveTicket(String token) {
        if (token != null && !token.isBlank()) {
            String raw = redis.opsForValue().get(tokenKeyPrefix + token);
            if (raw != null) {
                return new Ticket(token, Long.parseLong(raw));
            }
        }
        String newToken = UUID.randomUUID().toString();
        Long ticket = redis.opsForValue().increment(seqKey);
        long number = (ticket == null) ? 1L : ticket;
        redis.opsForValue().set(tokenKeyPrefix + newToken, Long.toString(number), tokenTtl);
        return new Ticket(newToken, number);
    }

    private long readLong(String key) {
        String v = redis.opsForValue().get(key);
        return (v == null) ? 0L : Long.parseLong(v);
    }
}
