package com.hinsight.user.controller;

import com.hinsight.exception.custom.user.InvalidPasswordException;
import com.hinsight.order.service.OrderService;
import com.hinsight.product.service.ProductService;
import com.hinsight.security.userdetails.CustomerUserDetails;
import com.hinsight.user.model.dto.PasswordChangeRequest;
import com.hinsight.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 마이페이지 컨트롤러 (일반고객 전용).
 * 접근 제어는 CustomerSecurityConfig 의 hasRole("CUSTOMER") 로 이미 보장된다
 * (/customer/products·login·signup 외 /customer/** 는 모두 고객 인증 필요).
 */
@Tag(name = "mypage-controller", description = "마이페이지 (프로필·최근 본/구매 상품·비밀번호 변경)")
@Controller
@RequiredArgsConstructor
@RequestMapping("/customer/mypage")
public class MyPageController {

    // 마이페이지 "최근 구매 상품" 에 노출할 상품 개수
    private static final int RECENT_PURCHASE_LIMIT = 5;

    private final UserService userService;
    private final OrderService orderService;
    private final ProductService productService;

    // 마이페이지: 상단 개인정보 요약 + 최근 본 상품 + 최근 구매 상품 + 비밀번호 변경 폼
    @Operation(summary = "마이페이지 조회", description = "프로필·최근 본 상품·최근 구매 상품·비밀번호 변경 폼을 렌더링한다")
    @GetMapping
    public String myPage(@AuthenticationPrincipal CustomerUserDetails principal, Model model) {
        model.addAttribute("profile", userService.getMyProfile(principal.getUserId()));
        model.addAttribute("recentViews", productService.getRecentViewedProducts(principal.getUserId()));
        model.addAttribute("recentPurchases",
                orderService.getRecentHistory(principal.getUserId(), RECENT_PURCHASE_LIMIT));
        model.addAttribute("passwordChangeRequest", new PasswordChangeRequest(null, null, null));
        return "customer/mypage/profile";
    }

    // 최근 본 상품 영역만 반환(뒤로가기 복귀 시 비동기 갱신용 프래그먼트)
    @Operation(summary = "최근 본 상품 조각", description = "최근 본 상품 영역만 HTML 조각으로 반환(비동기 갱신용)")
    @GetMapping("/recent-views")
    public String recentViews(@AuthenticationPrincipal CustomerUserDetails principal, Model model) {
        model.addAttribute("recentViews", productService.getRecentViewedProducts(principal.getUserId()));
        return "customer/mypage/profile :: recentViewContent";
    }

    // 비밀번호 변경 처리
    @Operation(summary = "비밀번호 변경", description = "현재 비밀번호 확인 후 새 비밀번호로 변경한다")
    @PostMapping("/password")
    public String changePassword(@Valid @ModelAttribute("passwordChangeRequest") PasswordChangeRequest request,
                                 BindingResult bindingResult,
                                 @AuthenticationPrincipal CustomerUserDetails principal,
                                 Model model) {
        // 교차 검증: 새 비밀번호 == 확인 (둘 다 입력된 경우만; 빈 값은 @NotBlank 가 처리)
        if (StringUtils.hasText(request.newPassword()) && StringUtils.hasText(request.confirmPassword())
                && !request.newPassword().equals(request.confirmPassword())) {
            bindingResult.rejectValue("confirmPassword", "mismatch", "새 비밀번호가 일치하지 않습니다.");
        }

        if (bindingResult.hasErrors()) {
            return renderWithProfile(principal, model);
        }

        try {
            userService.changePassword(principal.getUserId(), request.currentPassword(), request.newPassword());
        } catch (InvalidPasswordException e) {
            // API용 JSON 예외(GlobalExceptionHandler) 대신 폼 필드 에러로 표시
            bindingResult.rejectValue("currentPassword", "invalid", "현재 비밀번호가 올바르지 않습니다.");
            return renderWithProfile(principal, model);
        }

        return "redirect:/customer/mypage?pwChanged";
    }

    // 폼 재표시 시에도 상단 개인정보 + 최근 구매 상품이 보이도록 다시 실어준다
    private String renderWithProfile(CustomerUserDetails principal, Model model) {
        model.addAttribute("profile", userService.getMyProfile(principal.getUserId()));
        model.addAttribute("recentViews", productService.getRecentViewedProducts(principal.getUserId()));
        model.addAttribute("recentPurchases",
                orderService.getRecentHistory(principal.getUserId(), RECENT_PURCHASE_LIMIT));
        return "customer/mypage/profile";
    }
}
