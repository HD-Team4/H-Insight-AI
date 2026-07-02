package com.hinsight.user.controller;

import com.hinsight.exception.custom.user.DuplicateLoginIdException;
import com.hinsight.user.model.dto.SignupRequest;
import com.hinsight.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequiredArgsConstructor
@RequestMapping("/customer/users")
public class UserController {

    private final UserService userService;

    // 회원가입 폼
    @GetMapping("/signup")
    public String signupForm(Model model) {
        if (isAuthenticated()) {
            return "redirect:/customer/products"; // 로그인 상태면 가입 불필요
        }
        model.addAttribute("signupRequest", new SignupRequest(null, null, null, null, null, null, null));
        return "customer/auth/signup";
    }

    // 회원가입 처리
    @PostMapping("/signup")
    public String signup(@Valid @ModelAttribute("signupRequest") SignupRequest signupRequest,
                         BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            return "customer/auth/signup"; // 검증 실패 → 폼 재표시(에러 메시지 포함)
        }
        try {
            userService.register(signupRequest);
        } catch (DuplicateLoginIdException e) {
            // API용 JSON 예외 처리(GlobalExceptionHandler) 대신 폼 필드 에러로 표시
            bindingResult.rejectValue("loginId", "duplicate", e.getMessage());
            return "customer/auth/signup";
        }
        return "redirect:/customer/login?signup"; // 가입 완료 → 로그인 페이지
    }

    private boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken);
    }
}
