package com.hinsight.ai.rag.demo;

import com.hinsight.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <b>2차 방어(시맨틱 군집화) 시연용 API.</b> 실서비스 RAG/라이브봇 API 와 분리된 데모 전용 엔드포인트.
 * {@code /api/**} 체인에 속해 CSRF 없이 공개 호출되며, 시각화 페이지({@code /demo/semantic})만 사용한다.
 */
@Tag(name = "semantic-demo-api", description = "시맨틱 군집화(벡터공간·코사인 유사도) 시연 API — 실서비스 API와 분리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/demo/semantic")
public class SemanticDemoApiController {

    private static final Logger log = LoggerFactory.getLogger(SemanticDemoApiController.class);

    private final SemanticDemoService semanticDemoService;

    @Operation(summary = "기본 예시 질문", description = "스크립트 [2-②] 기본 예시 질문 목록을 반환한다")
    @GetMapping("/examples")
    public ApiResponse<List<String>> examples() {
        return ApiResponse.ok(semanticDemoService.defaultQuestions());
    }

    @Operation(summary = "임베딩·군집화·2D 투영",
            description = "질문들을 BGE-M3로 임베딩해 코사인 유사도·군집·PCA(2D) 좌표·LLM 절감량을 반환한다")
    @PostMapping("/analyze")
    public ApiResponse<SemanticSpaceResponse> analyze(@RequestBody AnalyzeRequest request) {
        try {
            List<String> questions = request == null ? null : request.questions();
            Double threshold = request == null ? null : request.threshold();
            Integer repeatThreshold = request == null ? null : request.repeatThreshold();
            return ApiResponse.ok(semanticDemoService.analyze(questions, threshold, repeatThreshold));
        } catch (Exception e) {
            log.warn("[시맨틱-데모] 분석 실패: {}", e.getMessage());
            return ApiResponse.fail("임베딩 모델(BGE-M3) 호출에 실패했습니다. 임베딩 Lambda(hf4-embedding) 상태를 확인하세요: " + e.getMessage());
        }
    }

    /**
     * @param questions       분석할 질문들(한 줄 = 들어온 채팅 1건). 비면 기본 예시 사용.
     * @param threshold       같은 군집으로 볼 코사인 임계치(null 이면 0.75)
     * @param repeatThreshold 트리거에 필요한 반복 횟수(null 이면 2)
     */
    public record AnalyzeRequest(List<String> questions, Double threshold, Integer repeatThreshold) {
    }
}
