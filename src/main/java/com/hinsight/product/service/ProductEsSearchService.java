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

    /** ES 검색 결과(상품 목록) + 오타 교정어 */
    public record SearchOutcome(List<Product> products, String suggestion) {}

    /**
     * 통합 상품 검색: ES 한 요청으로 ID + 교정어를 받아 DB에서 상품을 로드한다.
     * 오타 교정(did-you-mean) 여부 판단·재검색은 ProductService에서 담당
     */
    public SearchOutcome search(String keyword, List<Long> categoryIds, Integer minPrice, Integer maxPrice) {
        try {
            ProductEsDao.EsSearchResult es = productEsDao.search(keyword, categoryIds, minPrice, maxPrice);

            List<Product> products = es.ids().isEmpty()
                    ? Collections.emptyList()
                    : productDao.findByIds(es.ids()); // DB에서 실제 상품 정보 일괄 조회

            return new SearchOutcome(products, es.suggestion());

        } catch (IOException e) {
            log.error("Elasticsearch 검색 중 에러 발생", e);
            throw new RuntimeException("검색 시스템에 일시적인 오류가 발생했습니다.", e);
        }
    }
}