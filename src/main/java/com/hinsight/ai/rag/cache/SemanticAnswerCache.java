package com.hinsight.ai.rag.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hinsight.ai.embedding.Vectors;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;


@Component
public class SemanticAnswerCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticAnswerCache.class);
    private static final String KEY_PREFIX = "rag:qa:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private final Counter hitCounter;
    private final Counter missCounter;
    private final Counter tokensSavedCounter;

    @Value("${rag.cache.enabled:true}")                private boolean enabled;
    @Value("${rag.cache.similarity-threshold:0.92}")   private double simThreshold;   // 답변 재사용 유사도 하한(보수적)
    @Value("${rag.cache.ttl-seconds:1800}")            private long ttlSeconds;       // 캐시 신선도(기본 30분)
    @Value("${rag.cache.max-entries-per-product:50}")  private int maxEntries;        // 상품당 후보 캡

    public SemanticAnswerCache(StringRedisTemplate redis,
                               ObjectMapper objectMapper,
                               MeterRegistry meterRegistry) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.hitCounter = Counter.builder("rag.cache.requests")
                .tag("result", "hit").description("시맨틱 답변 캐시 히트 수").register(meterRegistry);
        this.missCounter = Counter.builder("rag.cache.requests")
                .tag("result", "miss").description("시맨틱 답변 캐시 미스 수").register(meterRegistry);
        this.tokensSavedCounter = Counter.builder("rag.cache.tokens_saved")
                .description("캐시 히트로 절약한 LLM 토큰(추정) 누적").register(meterRegistry);
    }

    /**
     * 유사 질문의 캐시된 답변을 조회한다. 없거나 캐시 비활성/장애면 {@link Optional#empty()}.
     *
     * @param productId 대상 상품
     * @param queryVec  질문 임베딩(BGE-M3, 1024d)
     */
    public Optional<Hit> lookup(long productId, float[] queryVec) {
        if (!enabled) return Optional.empty();
        try {
            List<String> rows = redis.opsForList().range(key(productId), 0, maxEntries - 1);
            if (rows == null || rows.isEmpty()) {
                missCounter.increment();
                return Optional.empty();
            }

            Entry best = null;
            double bestSim = -1.0;
            for (String row : rows) {
                Entry e = deserialize(row);
                if (e == null) continue;
                double sim = Vectors.cosine(queryVec, Vectors.fromBase64(e.embedding()));
                if (sim > bestSim) {
                    bestSim = sim;
                    best = e;
                }
            }

            if (best != null && bestSim >= simThreshold) {
                hitCounter.increment();
                long saved = best.promptTokensApprox() + approxTokens(best.answer());
                tokensSavedCounter.increment(saved);
                log.info("[RAG캐시] HIT productId={}, sim={}, 절약토큰≈{}, 캐시질문='{}'",
                        productId, String.format("%.3f", bestSim), saved, best.question());
                return Optional.of(new Hit(best.answer(), bestSim, best.question()));
            }

            missCounter.increment();
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("[RAG캐시] 조회 실패(미스로 처리) productId={}, msg={}", productId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * LLM 이 생성한 답변을 캐시에 적재한다. 실패해도 무시(응답 흐름에 영향 없음).
     * @param promptChars 이번 요청에서 LLM 에 실제로 넘긴 프롬프트(system+user) 글자수.
     *                    다음 히트 때 "아꼈을 프롬프트 토큰"을 추정하는 데 쓴다.
     */
    public void store(long productId, String question, float[] queryVec, String answer, int promptChars) {
        if (!enabled || answer == null || answer.isBlank()) return;
        try {
            Entry entry = new Entry(question, answer.strip(),
                    Vectors.toBase64(queryVec), approxTokens(promptChars), System.currentTimeMillis());
            String json = objectMapper.writeValueAsString(entry);
            String key = key(productId);
            redis.opsForList().leftPush(key, json);
            redis.opsForList().trim(key, 0, maxEntries - 1);   // 최근 maxEntries 건만 유지
            redis.expire(key, Duration.ofSeconds(ttlSeconds));
            log.info("[RAG캐시] STORE productId={}, q='{}', 프롬프트≈{}토큰", productId, question, approxTokens(promptChars));
        } catch (Exception e) {
            log.warn("[RAG캐시] 적재 실패(무시) productId={}, msg={}", productId, e.getMessage());
        }
    }

    private Entry deserialize(String row) {
        try {
            return objectMapper.readValue(row, Entry.class);
        } catch (Exception e) {
            return null;   // 포맷 깨진 항목은 건너뛴다
        }
    }

    /** 대략적인 토큰 수 추정. 한국어 혼합 텍스트 기준 대략 3자 ≈ 1토큰으로 잡는다(지표용 근사). */
    private int approxTokens(String text) {
        return text == null ? 0 : (int) Math.ceil(text.length() / 3.0);
    }

    private int approxTokens(int chars) {
        return (int) Math.ceil(chars / 3.0);
    }

    private String key(long productId) {
        return KEY_PREFIX + productId;
    }

    /** 캐시 히트 결과. */
    public record Hit(String answer, double similarity, String matchedQuestion) {
    }

    /** Redis 에 저장되는 캐시 1건. embedding 은 Base64(float[1024]). */
    record Entry(String question, String answer, String embedding, int promptTokensApprox, long createdAtEpochMs) {
    }
}
