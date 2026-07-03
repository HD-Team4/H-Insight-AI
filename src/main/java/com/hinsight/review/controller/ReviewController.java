package com.hinsight.review.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "review-controller", description = "리뷰 컨트롤러")
@Controller
@RequestMapping("/customer/reviews")
public class ReviewController {
}
