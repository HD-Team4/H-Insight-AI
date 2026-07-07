package com.hinsight.security;

import com.hinsight.security.handler.CustomerLoginSuccessHandler;
import com.hinsight.security.userdetails.CustomerUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 일반고객 보안 설정. /customer/** 전용 체인.
 * biz 체인(/biz/**)과 경로가 완전히 분리(서로소)되어 @Order 가 필요 없다.
 * (정적 자원 /css·/js·/images, 루트 / 는 어느 체인에도 안 걸려 보안필터 없이 그대로 서빙된다)
 */
@Configuration
@RequiredArgsConstructor
public class CustomerSecurityConfig {

    private final CustomerUserDetailsService customerUserDetailsService;
    private final CustomerLoginSuccessHandler customerLoginSuccessHandler;

    @Bean
    public SecurityFilterChain customerSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/customer/**")   // /biz/** 와 겹치지 않음 → 순서(@Order) 불필요
                .userDetailsService(customerUserDetailsService)
                .authorizeHttpRequests(auth -> auth
                        // 공개: 상품 브라우징 + 로그인/회원가입 + 대기열 부하 테스트 경량 엔드포인트
                        .requestMatchers("/customer/products/**", "/customer/login", "/customer/users/signup", "/customer/loadtest").permitAll()
                        // 그 외 고객 전용 기능(장바구니/주문/마이페이지 등)은 ROLE_CUSTOMER 필요
                        .anyRequest().hasRole("CUSTOMER")
                )
                .formLogin(form -> form
                        .loginPage("/customer/login")           // GET: 로그인 폼 (AuthController)
                        .loginProcessingUrl("/customer/login")  // POST: 인증 처리 (필터 담당)
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler(customerLoginSuccessHandler)
                        .failureUrl("/customer/login?error")
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/customer/logout")
                        .logoutSuccessUrl("/customer/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                )
                .build();
    }
}
