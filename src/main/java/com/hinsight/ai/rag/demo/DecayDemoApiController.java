package com.hinsight.ai.rag.demo;

import com.hinsight.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>3. 검색 필터링(반감기 지수 감쇠) 시연용 API.</b> 실서비스 검색 API 와 분리된 데모 전용.
 * {@code /api/**} 체인에 속해 공개 호출되며, 시각화 페이지({@code /demo/decay})만 사용한다.
 */
@Tag(name = "decay-demo-api", description = "반감기 지수 감쇠 재랭킹 시연 API — 실서비스 API와 분리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/cy/demo/decay")
public class DecayDemoApiController {

    private final DecayDemoService decayDemoService;

    @Operation(summary = "운영 기본값", description = "반감기·가중치의 실제 운영 config 기본값을 반환한다")
    @GetMapping("/defaults")
    public ApiResponse<DecayDemoService.Defaults> defaults() {
        return ApiResponse.ok(decayDemoService.defaults());
    }

    @Operation(summary = "감쇠 곡선·재랭킹 계산",
            description = "w(t)=2^(-Δt/h) 곡선과 최신순/유사도순/하이브리드 재랭킹 결과를 반환한다")
    @GetMapping("/compute")
    public ApiResponse<DecayResponse> compute(
            @RequestParam(required = false) Double halfLife,
            @RequestParam(required = false) Double wSim,
            @RequestParam(required = false) Integer cutoff) {
        return ApiResponse.ok(decayDemoService.compute(halfLife, wSim, cutoff));
    }
}
