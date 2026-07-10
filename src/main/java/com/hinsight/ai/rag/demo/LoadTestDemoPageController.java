package com.hinsight.ai.rag.demo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 1만건 토큰 부하 벤치마크 시각화 페이지 뷰.
 *
 * <p>{@code /demo/**} 는 어떤 시큐리티 체인에도 매칭되지 않아 공개 서빙된다. 다른 방어 데모와 함께
 * 발표/포트폴리오용 독립 페이지를 이룬다.</p>
 */
@Tag(name = "loadtest-demo-page", description = "1만건 토큰 부하 벤치마크 시각화 페이지")
@Controller
public class LoadTestDemoPageController {

    @Operation(summary = "토큰 부하 벤치마크 시각화", description = "1만 채팅을 실 파이프라인에 태워 실제 호출·토큰 절감을 보여준다")
    @GetMapping("/demo/loadtest")
    public String loadTestDemo() {
        return "demo/loadtest";
    }
}
