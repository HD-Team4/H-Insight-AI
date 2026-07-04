package com.hinsight.biz.reviewanalysis.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.hinsight.biz.reviewanalysis.service.ReviewAnalysisService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Tag(name = "review-analysis-controller", description = "리뷰 분석 컨트롤러")
@Controller
@RequiredArgsConstructor
@RequestMapping("/biz/review-analysis")
public class ReviewAnalysisController {

    private final ReviewAnalysisService reviewAnalysisService;

    // 상품 분석 화면 (주간 급등/급락 TOP5 → 클릭 시 통계·리뷰 분석·AI 전략)
    @GetMapping
    public String analysisPage() {
        return "biz/reviewanalysis/analysis";
    }

    // 상품 분석 마트 JSON (프론트가 fetch)
    @ResponseBody
    @GetMapping("/data")
    public JsonNode data() {
        return reviewAnalysisService.getProductAnalysis();
    }
}
