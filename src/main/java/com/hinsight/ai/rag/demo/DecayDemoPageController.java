package com.hinsight.ai.rag.demo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 3. 검색 필터링(반감기 지수 감쇠) 시각화 페이지 뷰.
 *
 * <p>{@code /demo/**} 는 어떤 시큐리티 체인에도 매칭되지 않아 공개 서빙된다. 1·2차 방어 데모와 함께
 * 발표/포트폴리오용 독립 페이지를 이룬다.</p>
 */
@Tag(name = "decay-demo-page", description = "반감기 지수 감쇠 시각화 페이지")
@Controller
public class DecayDemoPageController {

    @Operation(summary = "지수 감쇠 재랭킹 시각화", description = "시간에 따른 리뷰 가중치 감쇠와 하이브리드 재랭킹을 보여준다")
    @GetMapping("/demo/decay")
    public String decayDemo() {
        return "demo/decay";
    }
}
