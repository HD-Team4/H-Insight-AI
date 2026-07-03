package com.hinsight.recommendation.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "recommendation-controller", description = "추천 컨트롤러")
@Controller
@RequestMapping("/customer/recommendations")
public class RecommendationController {
}
