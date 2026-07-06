package com.hinsight.ai.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.LambdaClient;

import java.util.Map;

/**
 * BGE-M3 임베딩 클라이언트 — AWS Lambda(hf4-embedding) SDK invoke.
 * 적재 파이프라인과 동일한 BGE-M3(dense, 1024d)로 쿼리를 임베딩한다 (정합성 실측 완료).
 * invoke payload {"input": text}  ->  {"embeddings": [[...1024...]], "dim": 1024, "ms": ...}
 *
 * <p>모델은 Lambda 컨테이너 이미지에 베이크(ONNX fp32)돼 있고 EventBridge가 5분마다 워밍 핑을 보내
 * 상주시킨다 → 앱은 기동 시 모델을 메모리에 올리지 않는다 (기존 EC2 로컬 Ollama 대체, 상주 ~1.2GB 해방).
 * 부팅 직후 워밍업 1회는 연결 확인 + 혹시 식은 환경의 콜드 로딩을 미리 치르는 용도로 유지.</p>
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final LambdaClient lambda;
    private final ObjectMapper objectMapper;
    private final String functionName;

    public EmbeddingService(LambdaClient lambda,
                            ObjectMapper objectMapper,
                            @Value("${embedding.lambda.function-name:hf4-embedding}") String functionName) {
        this.lambda = lambda;
        this.objectMapper = objectMapper;
        this.functionName = functionName;
    }

    public float[] embed(String text) {
        JsonNode root = invoke(Map.of("input", text));
        JsonNode embeddings = root.path("embeddings");
        if (!embeddings.isArray() || embeddings.isEmpty()) {
            throw new IllegalStateException("임베딩 Lambda 응답이 비어 있습니다. (function=" + functionName + ", 응답=" + root + ")");
        }
        JsonNode v = embeddings.get(0);
        float[] out = new float[v.size()];
        for (int i = 0; i < v.size(); i++) {
            out[i] = (float) v.get(i).asDouble();
        }
        return out;
    }

    private JsonNode invoke(Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            InvokeResponse resp = lambda.invoke(b -> b.functionName(functionName)
                    .payload(SdkBytes.fromUtf8String(json)));
            JsonNode root = objectMapper.readTree(resp.payload().asUtf8String());
            if (resp.functionError() != null) {
                throw new IllegalStateException("임베딩 Lambda 실행 오류: " + root);
            }
            return root;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("임베딩 Lambda 응답 파싱 실패", e);
        }
    }

    /** 부팅 직후 백그라운드 워밍 핑. 실패해도 앱에는 영향 없음(연결/권한 문제를 로그로 조기 표면화). */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            long t0 = System.currentTimeMillis();
            JsonNode root = invoke(Map.of("warm", true));
            log.info("[임베딩] Lambda 워밍 완료 ({}, coldLoadMs={}, {}ms)",
                    functionName, root.path("coldLoadMs").asInt(-1), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.warn("[임베딩] Lambda 워밍 실패(무시): {}", e.getMessage());
        }
    }
}
