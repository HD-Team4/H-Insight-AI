package com.hinsight.config;

import com.hinsight.waitingroom.interceptor.WaitingRoomInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final WaitingRoomInterceptor waitingRoomInterceptor;

    /** 가상 대기열을 붙일 폭주 경로(콤마 구분) — application.yml waiting-room.paths */
    @Value("${waiting-room.paths:}")
    private String[] waitingRoomPaths;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (waitingRoomPaths.length > 0) {
            registry.addInterceptor(waitingRoomInterceptor).addPathPatterns(waitingRoomPaths);
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 대기 페이지(CDN 오리진)에서 status 폴링 허용 — 쿠키 없는 공개 조회 API라 전체 오리진 허용
        registry.addMapping("/api/waiting-room/**").allowedMethods("GET");
    }
}
