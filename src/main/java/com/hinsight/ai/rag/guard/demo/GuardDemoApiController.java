package com.hinsight.ai.rag.guard.demo;

import com.hinsight.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <b>1차 방어(Aho-Corasick 금칙어 필터) 시연용 API.</b>
 *
 * <p>실서비스 RAG/리뷰봇 API({@code /customer/rag} 등)와 <b>완전히 분리</b>된 데모 전용 엔드포인트다.
 * {@code /api/**} 체인에 속해 CSRF 없이 공개 호출되며, 시각화 페이지({@code /demo/guard})만 사용한다.</p>
 */
@Tag(name = "guard-demo-api", description = "금칙어 사전 게이팅(Aho-Corasick) 시연 API — 실서비스 API와 분리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/demo/guard")
public class GuardDemoApiController {

    private final GuardDemoService guardDemoService;

    @Operation(summary = "금칙어 사전 조회", description = "Trie 시각화를 위해 로딩된 금칙어(정규화된 소문자) 목록을 반환한다")
    @GetMapping("/dictionary")
    public ApiResponse<List<String>> dictionary() {
        return ApiResponse.ok(guardDemoService.dictionary());
    }

    @Operation(summary = "금칙어 단일 스캔", description = "입력을 Aho-Corasick 로 1회 스캔해 매칭 위치·차단 여부·소요 시간을 반환한다")
    @PostMapping("/scan")
    public ApiResponse<GuardScanResponse> scan(@RequestBody ScanRequest request) {
        return ApiResponse.ok(guardDemoService.scan(request == null ? null : request.text()));
    }

    @Operation(summary = "도입 전/후 벤치마크",
            description = "금칙어 개수(M)를 늘려가며 단순 contains 반복(O(N·M)) vs Aho-Corasick(O(N)) 실측치를 반환한다")
    @GetMapping("/benchmark")
    public ApiResponse<BenchmarkResponse> benchmark() {
        return ApiResponse.ok(guardDemoService.benchmark());
    }

    /** @param text 검사할 원본 문장 */
    public record ScanRequest(String text) {
    }
}
