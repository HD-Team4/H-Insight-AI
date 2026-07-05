package com.hinsight.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger(OpenAPI) 문서 메타데이터 설정.
 * springdoc-openapi 가 컨트롤러 매핑을 스캔해 자동으로 문서를 생성하며,
 * 여기서는 제목/설명/버전 등 상단 정보만 지정한다.
 *
 * - UI:        /swagger-ui/index.html  (또는 /swagger-ui.html 리다이렉트)
 * - JSON 스펙: /v3/api-docs
 * 두 경로 모두 /customer/** · /biz/** 어느 시큐리티 체인에도 매칭되지 않아
 * 별도 인가 설정 없이 접근 가능하다.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI hInsightOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("H-Insight-AI API")
                        .description("""
                                홈쇼핑 판매 분석·방송 전략 추천 플랫폼 백엔드 API.

                                주요 도메인: 상품/검색 · 주문/장바구니 · 리뷰 · 마이페이지 · 라이브 방송/채팅 ·
                                챗봇/RAG · Biz 대시보드/상품분석 · 관리(ES·RAG).
                                컨트롤러별 태그로 그룹화되어 있으며, 각 엔드포인트에 요약·설명이 붙어 있다.

                                인증: 고객(/customer/**)·기업(/biz/**) 폼 로그인 기반 세션.""")
                        .version("v0.0.1"));
    }
}
