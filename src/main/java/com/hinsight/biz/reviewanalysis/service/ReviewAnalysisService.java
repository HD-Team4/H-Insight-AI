package com.hinsight.biz.reviewanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * 상품 분석 데이터 서빙 — 주간 급등/급락 TOP5 + 상품별 리뷰 감성·키워드 + LLM 향상 전략.
 * <p>현재는 상품 분석 마트(products.json)를 클래스패스 번들로 읽는다.
 * 클라우드 전환 시 이 로드 지점만 S3(mart/products/latest.json) 읽기로 교체하면
 * 프론트/계약은 그대로 유지된다. (DashboardService와 동일 패턴)
 */
@Service
@RequiredArgsConstructor
public class ReviewAnalysisService {

    private static final String MART_RESOURCE = "dummy/products.json";

    private final ObjectMapper objectMapper;

    public JsonNode getProductAnalysis() {
        try (InputStream is = new ClassPathResource(MART_RESOURCE).getInputStream()) {
            return objectMapper.readTree(is);
        } catch (IOException e) {
            throw new UncheckedIOException("상품 분석 mart 로드 실패: " + MART_RESOURCE, e);
        }
    }
}
