package com.hinsight.security.handler;

import com.hinsight.security.userdetails.BizUserDetails;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 기업 로그인 성공 핸들러.
 * 세션에 bizId 를 심어 이후 biz 기능(대시보드/리포트 등)이 어느 기업인지 식별하게 하고,
 * 기본 착지 페이지를 biz 대시보드로 둔다. (고객의 LoginSuccessHandler 와 동일한 패턴)
 */
@Component
public class BizLoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    public BizLoginSuccessHandler() {
        setDefaultTargetUrl("/biz/dashboard");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        if (authentication.getPrincipal() instanceof BizUserDetails bizUserDetails) {
            request.getSession().setAttribute("bizId", bizUserDetails.getBizId());
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
