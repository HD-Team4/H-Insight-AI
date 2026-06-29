package com.hinsight.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class CustomerSecurityConfig {

    @Bean
    @Order(2)
    public SecurityFilterChain customerSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
