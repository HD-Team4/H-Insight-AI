package com.hinsight.security;

import com.hinsight.security.handler.BizLoginSuccessHandler;
import com.hinsight.security.userdetails.BizUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 기업(biz) 보안 설정. /biz/** 전용 체인(Order 1, 고객 체인보다 먼저 매칭).
 * 고객과 완전히 분리된 로그인 지점(/biz/login)과 ROLE_BIZ 기반 인가를 가진다.
 */
@Configuration
@RequiredArgsConstructor
public class BizSecurityConfig {

    private final BizUserDetailsService bizUserDetailsService;
    private final BizLoginSuccessHandler bizLoginSuccessHandler;

    @Bean
    public SecurityFilterChain bizSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/biz/**")
                // 이 체인 전용 인증 공급자(biz_users 조회 + BCrypt). 고객 체인과 분리.
                .userDetailsService(bizUserDetailsService)
                .authorizeHttpRequests(auth -> auth
                        // 로그인 페이지/처리 URL 은 formLogin.permitAll() 이 열어줌.
                        // 나머지 /biz/** 는 모두 기업 로그인 필요.
                        .anyRequest().hasRole("BIZ")
                )
                .formLogin(form -> form
                        .loginPage("/biz/login")           // GET: 기업 로그인 폼
                        .loginProcessingUrl("/biz/login")  // POST: 인증 처리(필터 담당)
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler(bizLoginSuccessHandler)
                        .failureUrl("/biz/login?error")
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/biz/logout")
                        .logoutSuccessUrl("/biz/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                )
                .build();
    }
}
