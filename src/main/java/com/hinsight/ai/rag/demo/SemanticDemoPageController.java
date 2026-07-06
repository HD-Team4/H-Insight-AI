package com.hinsight.ai.rag.demo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 2차 방어(시맨틱 군집화) 시각화 페이지 뷰.
 *
 * <p>{@code /demo/**} 는 어떤 시큐리티 체인에도 매칭되지 않아 보안필터 없이 공개 서빙된다. 발표/포트폴리오용
 * 독립 페이지이며, 1차 방어 페이지({@code /demo/guard})와 짝을 이룬다.</p>
 */
@Tag(name = "semantic-demo-page", description = "시맨틱 군집화 시각화 페이지")
@Controller
public class SemanticDemoPageController {

    @Operation(summary = "시맨틱 군집화 시각화", description = "벡터공간·코사인 유사도로 유사 질문을 묶어 LLM 호출을 줄이는 과정을 보여준다")
    @GetMapping("/demo/semantic")
    public String semanticDemo() {
        return "demo/semantic";
    }
}
