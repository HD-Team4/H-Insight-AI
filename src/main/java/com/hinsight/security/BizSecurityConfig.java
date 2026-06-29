package com.hinsight.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class BizSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain bizSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/biz/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
