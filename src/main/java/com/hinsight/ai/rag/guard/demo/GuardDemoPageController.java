package com.hinsight.ai.rag.guard.demo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 1차 방어(Aho-Corasick 금칙어 필터) 시각화 페이지 뷰.
 *
 * <p>{@code /demo/**} 는 어떤 시큐리티 체인에도 매칭되지 않아 보안필터 없이 공개 서빙된다
 * (루트 {@code /}·정적 자원과 동일). 발표/포트폴리오용 독립 페이지로, 실서비스 화면과 섞이지 않는다.</p>
 */
@Tag(name = "guard-demo-page", description = "금칙어 사전 게이팅 시각화 페이지")
@Controller
public class GuardDemoPageController {

    @Operation(summary = "금칙어 필터 시각화", description = "Trie/Aho-Corasick 로 금칙어가 즉시 차단되는 과정을 보여준다")
    @GetMapping("/demo/guard")
    public String guardDemo() {
        return "demo/guard";
    }
}
