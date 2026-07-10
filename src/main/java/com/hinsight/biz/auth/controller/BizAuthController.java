package com.hinsight.biz.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 기업 인증 화면 컨트롤러. 로그인 처리(POST)/로그아웃은 Spring Security 필터가 담당하고,
 * 여기서는 기업 로그인 페이지 렌더링만 한다.
 */
@Tag(name = "biz-auth-controller", description = "기업 인증 컨트롤러")
@Controller
public class BizAuthController {

    @Operation(summary = "기업 로그인 페이지", description = "기업 로그인 페이지를 렌더링한다. 이미 기업 로그인 상태면 대시보드로 리다이렉트")
    @GetMapping("/biz/login")
    public String loginPage() {
        if (isBizAuthenticated()) {
            return "redirect:/biz/dashboard"; // 이미 기업 로그인 상태면 대시보드로
        }
        return "biz/auth/login";
    }

    // ROLE_BIZ 로 이미 인증된 경우만 true. (고객이 이 페이지에 와도 리다이렉트 루프가 안 생기도록)
    private boolean isBizAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken)
                && auth.getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals("ROLE_BIZ"));
    }
}
