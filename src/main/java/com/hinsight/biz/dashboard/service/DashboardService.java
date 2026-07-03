package com.hinsight.biz.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Biz 대시보드 데이터 서빙.
 * <p>현재는 집계 파이프라인(KAN-63)이 만든 mart JSON을 클래스패스 번들로 읽는다.
 * 클라우드 전환(KAN-81) 시 이 로드 지점만 S3(mart/dashboard/latest.json) 읽기로 교체하면
 * 프론트/계약은 그대로 유지된다.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final String MART_RESOURCE = "dummy/dashboard.json";
    private static final List<String> PERIODS = List.of("1w", "1m", "6m", "1y");
    private static final String DEFAULT_PERIOD = "1m";

    private final ObjectMapper objectMapper;

    /** 기간(1w/1m/6m/1y) 슬라이스 반환. 잘못된 값이면 기본(1개월). */
    public JsonNode getDashboard(String period) {
        String key = PERIODS.contains(period) ? period : DEFAULT_PERIOD;
        JsonNode slice = loadMart().get(key);
        return (slice != null) ? slice : loadMart().get(DEFAULT_PERIOD);
    }

    private JsonNode loadMart() {
        try (InputStream is = new ClassPathResource(MART_RESOURCE).getInputStream()) {
            return objectMapper.readTree(is);
        } catch (IOException e) {
            throw new UncheckedIOException("대시보드 mart 로드 실패: " + MART_RESOURCE, e);
        }
    }
}
