package com.hinsight.ai.mcp.notion;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "notion")
public class NotionProperties {

    /** 노션 internal integration secret (ntn_...). 미설정 시 전송 비활성. */
    private String token;

    private String version = "2026-03-11";
    private String baseUrl = "https://api.notion.com/v1";

    public boolean isConfigured() {
        return token != null && !token.isBlank();
    }
}
