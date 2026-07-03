package com.hinsight.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 인증 화면 컨트롤러. 로그인 처리(POST /login)/로그아웃(POST /logout)은
 * Spring Security 필터가 담당하고, 여기서는 로그인 페이지 렌더링만 한다.
 */
@Tag(name = "auth-controller", description = "인증 컨트롤러")
@Controller
public class AuthController {

    @Operation(summary = "로그인 페이지", description = "고객 로그인 페이지를 렌더링한다. 이미 로그인 상태면 상품목록으로 리다이렉트")
    @GetMapping("/customer/login")
    public String loginPage() {
        if (isAuthenticated()) {
            return "redirect:/customer/products"; // 이미 로그인한 유저는 상점 메인으로
        }
        return "customer/auth/login";
    }

    private boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken);
    }
}
