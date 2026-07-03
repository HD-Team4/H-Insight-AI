package com.hinsight.ai.embedding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Ollama BGE-M3 임베딩 클라이언트.
 * 적재 파이프라인과 동일한 BGE-M3(dense, 1024d)로 쿼리를 임베딩한다 (정합성 실측 완료).
 * POST {base-url}/api/embed  {"model":"bge-m3","input": text}  ->  { "embeddings": [[...1024...]] }
 */
@Service
public class EmbeddingService {

    private final RestClient rest;
    private final String model;

    public EmbeddingService(
            @Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${ollama.embedding-model:bge-m3}") String model) {
        this.rest = RestClient.create(baseUrl);
        this.model = model;
    }

    public float[] embed(String text) {
        EmbedResponse resp = rest.post()
                .uri("/api/embed")
                .body(Map.of("model", model, "input", text))
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

    private record EmbedResponse(List<List<Double>> embeddings) {
    }
}
