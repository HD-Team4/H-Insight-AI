package com.hinsight.review.controller;

import com.hinsight.review.model.dto.ReviewCreateRequest;
import com.hinsight.review.service.ReviewService;
import com.hinsight.security.userdetails.CustomerUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Tag(name = "review-controller", description = "리뷰 컨트롤러")
@Controller
@RequiredArgsConstructor
@RequestMapping("/customer/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "리뷰 작성", description = "로그인 고객이 상품 상세에서 별점·내용을 등록한다. 감성분석은 배치(리뷰 Lambda)에서 처리")
    @PostMapping
    public String createReview(@AuthenticationPrincipal CustomerUserDetails user,
                               @ModelAttribute ReviewCreateRequest request,
                               RedirectAttributes redirectAttributes) {
        if (user == null) {
            return "redirect:/customer/login";
        }
        try {
            reviewService.createReview(user.getUserId(), request);
            redirectAttributes.addFlashAttribute("reviewMessage", "리뷰가 등록되었습니다.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("reviewError", e.getMessage());
        }
        Long productId = request.getProductId();
        String target = (productId == null) ? "/customer/products" : "/customer/products/" + productId;
        return "redirect:" + target + "#productReviews";
    }
}
