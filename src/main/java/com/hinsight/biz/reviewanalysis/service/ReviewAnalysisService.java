package com.hinsight.biz.reviewanalysis.service;

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

/**
 * 상품 분석 데이터 서빙 — 주간 상승률/하락률 TOP5 + 상품별 리뷰 감성·키워드 + LLM 향상 전략.
 * <p>전략 Lambda가 만든 마트를 S3(mart/products/latest.json)에서 읽는다(KAN-106).
 * S3 조회 실패 시 클래스패스 dummy로 폴백. 계약은 동일. (DashboardService와 동일 패턴)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewAnalysisService {

    private static final String BUCKET = "hf4-datalake";
    private static final String MART_S3_KEY = "mart/products/latest.json";
    private static final String MART_RESOURCE = "dummy/products.json";

    private final ObjectMapper objectMapper;
    private final S3Client s3Client;

    public JsonNode getProductAnalysis() {
        try {
            byte[] bytes = s3Client.getObjectAsBytes(
                    req -> req.bucket(BUCKET).key(MART_S3_KEY)).asByteArray();
            return objectMapper.readTree(bytes);
        } catch (Exception e) {
            log.warn("S3 상품분석 마트 로드 실패({}), 클래스패스 dummy로 폴백: {}", MART_S3_KEY, e.getMessage());
            try (InputStream is = new ClassPathResource(MART_RESOURCE).getInputStream()) {
                return objectMapper.readTree(is);
            } catch (IOException io) {
                throw new MartLoadException(io);
            }
        }
    }
}
