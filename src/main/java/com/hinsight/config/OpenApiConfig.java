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
                        .description("H-Insight-AI 백엔드 API 문서")
                        .version("v0.0.1"));
    }
}
