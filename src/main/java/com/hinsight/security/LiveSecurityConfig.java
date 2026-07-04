package com.hinsight.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 라이브 방 + 실시간 채팅(/live/**, /ws-live/**) 전용 체인.
 *
 * 이 경로는 로그인·익명 모두 열려 있어야 하므로 전부 permitAll
 * 다만 보안필터 밖(무체인)에 두는 대신 permitAll 체인으로 감싸는 이유는,
 * SecurityContextHolderFilter 가 세션의 인증정보를 로드해줘야
 * STOMP 핸드셰이크에서 "로그인 시청자 이름"을 알 수 있기 때문이다.
 * (익명은 그대로 게스트 처리)
 * REST viewers POST 와 SockJS 핸드셰이크는 CSRF 토큰이 없으므로 CSRF 를 끈다.
 */
@Configuration
public class LiveSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain liveSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/live/**", "/ws-live/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
