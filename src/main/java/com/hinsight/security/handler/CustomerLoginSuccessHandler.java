package com.hinsight.security.handler;

import com.hinsight.security.userdetails.CustomerUserDetails;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 고객 로그인 성공 핸들러.
 * SecurityContext(권한/role)와 별도로, 세션에 userId 를 심어
 * 기존 기능(CartController/OrderController 가 session.getAttribute("userId") 사용)이
 * 코드 변경 없이 실제 로그인 유저로 동작하도록 연결한다. (기업용은 BizLoginSuccessHandler)
 */
@Component
public class CustomerLoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    public CustomerLoginSuccessHandler() {
        setDefaultTargetUrl("/customer/products"); // 저장된 요청이 없으면 상점 메인으로
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        if (authentication.getPrincipal() instanceof CustomerUserDetails userDetails) {
            request.getSession().setAttribute("userId", userDetails.getUserId());
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
