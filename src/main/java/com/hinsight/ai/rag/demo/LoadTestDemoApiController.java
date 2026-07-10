package com.hinsight.ai.rag.demo;

import com.hinsight.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>1만건 토큰 부하 벤치마크 시연 API.</b>
 *
 * <p>실서비스 가드·임베딩·군집화·Redis 캐시 빈을 그대로 태워 실측한다. 데모 전용({@code /api/cy/demo/**})이라
 * 공개 호출되며 시각화 페이지({@code /demo/loadtest})만 사용한다.</p>
 */
@Tag(name = "loadtest-demo-api", description = "1만건 토큰 부하 벤치마크(실측) — 가드·임베딩·군집화·캐시 실서비스 빈 사용")
@RestController
@RequestMapping("/api/cy/demo/loadtest")
public class LoadTestDemoApiController {

    private final LoadTestDemoService loadTestDemoService;

    public LoadTestDemoApiController(LoadTestDemoService loadTestDemoService) {
        this.loadTestDemoService = loadTestDemoService;
    }

    @Operation(summary = "부하 벤치마크 실행",
            description = "합성 채팅 count 건을 실제 가드·BGE-M3 임베딩·코사인 군집화·Redis 캐시에 태워 " +
                    "실제 Gemini 호출 횟수·캐시 HIT율·처리시간을 실측한다(Gemini 실호출은 제외).")
    @GetMapping("/run")
    public ApiResponse<LoadTestResponse> run(
            @RequestParam(required = false) Integer count,
            @RequestParam(required = false) Integer windowSec,
            @RequestParam(required = false) Integer products) {
        return ApiResponse.ok(loadTestDemoService.run(count, windowSec, products));
    }
}
