package com.hinsight.ai.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Ollama BGE-M3 임베딩 클라이언트.
 * 적재 파이프라인과 동일한 BGE-M3(dense, 1024d)로 쿼리를 임베딩한다 (정합성 실측 완료).
 * POST {base-url}/api/embed  {"model":"bge-m3","input": text, "keep_alive": ...}  ->  { "embeddings": [[...1024...]] }
 *
 * <p>keep_alive 로 모델을 메모리에 상주시키고, 부팅 직후 워밍업 1회로 콜드 로딩(수십 초)을 미리 치른다.
 * → 첫 사용자 요청이 모델 로딩을 기다리지 않는다.</p>
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final RestClient rest;
    private final String model;
    private final String keepAlive;

    public EmbeddingService(
            @Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${ollama.embedding-model:bge-m3}") String model,
            @Value("${ollama.keep-alive:30m}") String keepAlive) {
        this.rest = RestClient.create(baseUrl);
        this.model = model;
        this.keepAlive = keepAlive;
    }

    public float[] embed(String text) {
        EmbedResponse resp = rest.post()
                .uri("/api/embed")
                .body(Map.of("model", model, "input", text, "keep_alive", keepAlive))
                .retrieve()
                .body(EmbedResponse.class);

        if (resp == null || resp.embeddings() == null || resp.embeddings().isEmpty()) {
            throw new IllegalStateException("Ollama 임베딩 응답이 비어 있습니다. (모델=" + model + ")");
        }
        List<Double> v = resp.embeddings().get(0);
        float[] out = new float[v.size()];
        for (int i = 0; i < v.size(); i++) {
            out[i] = v.get(i).floatValue();
        }
        return out;
    }

    /** 부팅 직후 백그라운드로 모델을 미리 로딩(워밍업). 실패해도 앱에는 영향 없음. */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            long t0 = System.currentTimeMillis();
            embed("워밍업");
            log.info("[임베딩] 모델 워밍업 완료 ({}, {}ms)", model, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.warn("[임베딩] 워밍업 실패(무시): {}", e.getMessage());
        }
    }

    private record EmbedResponse(List<List<Double>> embeddings) {
    }
}
