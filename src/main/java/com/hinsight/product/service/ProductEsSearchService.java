package com.hinsight.product.service;

import com.hinsight.product.dao.ProductDao;
import com.hinsight.product.dao.ProductEsDao;
import com.hinsight.product.model.vo.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductEsSearchService {

    private final ProductEsDao productEsDao; // ES 접근용 DAO
    private final ProductDao productDao;     // MyBatis DB 접근용 DAO

    /**
     * 통합 상품 검색 기능: ES에서 ID 검색 후 DB에서 최종 데이터 로드
     */
    public List<Product> searchProducts(String keyword, List<Long> categoryIds, Integer minPrice, Integer maxPrice) {
        try {
            // 1. 오타 교정어가 있다면 교정어 적용
            String suggestedKeyword = productEsDao.suggest(keyword);
            String finalKeyword = (suggestedKeyword != null) ? suggestedKeyword : keyword;

            // 2. ES에서 스코어(정확도) 순으로 정렬된 ID 목록 조회
            List<Long> productIds = productEsDao.searchIds(finalKeyword, categoryIds, minPrice, maxPrice);

            if (productIds.isEmpty()) {
                return Collections.emptyList();
            }

            // 보내주신 ProductDao의 findByIds 메서드로 DB에서 실제 상품 정보 일괄 조회
            // (주의: MyBatis에서 IN 쿼리 조회 시, ES가 보장해 준 ID 정렬 순서가 깨지지 않도록
            return productDao.findByIds(productIds);

        } catch (IOException e) {
            log.error("Elasticsearch 검색 중 에러 발생", e);
            throw new RuntimeException("검색 시스템에 일시적인 오류가 발생했습니다.", e);
        }
    }
}