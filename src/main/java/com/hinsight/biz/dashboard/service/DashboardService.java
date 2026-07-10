package com.hinsight.biz.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hinsight.exception.custom.biz.MartLoadException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Biz 대시보드 데이터 서빙.
 * <p>집계 파이프라인이 만든 mart JSON을 S3(mart/dashboard/latest.json)에서 읽는다(KAN-106).
 * S3 조회 실패 시 클래스패스 dummy로 폴백해 데모/오프라인에서도 화면이 뜬다. 계약은 동일.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final String BUCKET = "hf4-datalake";
    private static final String MART_S3_KEY = "mart/dashboard/latest.json";
    private static final String MART_RESOURCE = "dummy/dashboard.json";
    private static final List<String> PERIODS = List.of("1w", "1m", "6m", "1y");
    private static final String DEFAULT_PERIOD = "1m";

    private final ObjectMapper objectMapper;
    private final S3Client s3Client;

    /** 기간(1w/1m/6m/1y) 슬라이스 반환. 잘못된 값이면 기본(1개월). */
    public JsonNode getDashboard(String period) {
        String key = PERIODS.contains(period) ? period : DEFAULT_PERIOD;
        JsonNode mart = loadMart();
        JsonNode slice = mart.get(key);
        return (slice != null) ? slice : mart.get(DEFAULT_PERIOD);
    }

    private JsonNode loadMart() {
        try {
            byte[] bytes = s3Client.getObjectAsBytes(
                    req -> req.bucket(BUCKET).key(MART_S3_KEY)).asByteArray();
            return objectMapper.readTree(bytes);
        } catch (Exception e) {
            log.warn("S3 대시보드 마트 로드 실패({}), 클래스패스 dummy로 폴백: {}", MART_S3_KEY, e.getMessage());
            try (InputStream is = new ClassPathResource(MART_RESOURCE).getInputStream()) {
                return objectMapper.readTree(is);
            } catch (IOException io) {
                throw new MartLoadException(io);
            }
        }
    }
}
