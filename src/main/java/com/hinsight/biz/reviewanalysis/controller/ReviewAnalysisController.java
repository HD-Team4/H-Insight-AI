package com.hinsight.biz.reviewanalysis.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "review-analysis-controller", description = "리뷰 분석 컨트롤러")
@Controller
@RequestMapping("/biz/review-analysis")
public class ReviewAnalysisController {
}
