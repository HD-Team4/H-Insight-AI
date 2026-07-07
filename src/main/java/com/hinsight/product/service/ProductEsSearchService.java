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
     * 검색 결과(상품 목록) + 전체 매칭 건수 + 오타 교정(did-you-mean) 정보.
     * corrected=true 이면 originalKeyword 로는 0건이라 correctedKeyword 로 재검색한 결과다.
     */
    public record SearchOutcome(List<Product> products, long total,
                                String originalKeyword, String correctedKeyword, boolean corrected) {

        static SearchOutcome plain(List<Product> products, long total) {
            return new SearchOutcome(products, total, null, null, false);
        }

        static SearchOutcome corrected(List<Product> products, long total, String original, String corrected) {
            return new SearchOutcome(products, total, original, corrected, true);
        }
    }

    /**
     * 통합 상품 검색 + 오타 교정 재검색까지 담당한다.
     * 1) 원본 키워드로 검색해 결과가 있으면(정상어) 그대로 반환
     * 2) 0건이면서 교정어가 있으면(오타) 교정어로 한 번 더 검색해 반환
     * 3) 어느 쪽도 결과가 없으면 빈 결과 반환
     */
    public SearchOutcome search(String keyword, List<Long> categoryIds, Integer minPrice, Integer maxPrice,
                                int offset, int limit) {
        EsHit hit = queryOnce(keyword, categoryIds, minPrice, maxPrice, offset, limit);

        // 1. 결과가 있으면(정상어) 그대로 반환 (왕복 1회)
        if (!hit.products().isEmpty()) {
            return SearchOutcome.plain(hit.products(), hit.total());
        }

        // 2. 결과 0건 + 교정어가 있으면(오타) 교정어로 한 번 더 검색
        String corrected = hit.suggestion();
        if (corrected != null && !corrected.equalsIgnoreCase(keyword)) {
            EsHit alt = queryOnce(corrected, categoryIds, minPrice, maxPrice, offset, limit);
            if (!alt.products().isEmpty()) {
                return SearchOutcome.corrected(alt.products(), alt.total(), keyword, corrected);
            }
        }

        // 3. 원본·교정어 모두 결과 없음
        return SearchOutcome.plain(List.of(), hit.total());
    }

    /** 단일 ES 요청 결과: 상품 목록 + 교정어 + 전체 건수 */
    private record EsHit(List<Product> products, String suggestion, long total) {}

    /** ES 한 요청으로 ID + 교정어 + 전체 건수를 받아 DB에서 해당 페이지 상품을 로드한다. */
    private EsHit queryOnce(String keyword, List<Long> categoryIds, Integer minPrice, Integer maxPrice,
                            int offset, int limit) {
        try {
            ProductEsDao.EsSearchResult es = productEsDao.search(keyword, categoryIds, minPrice, maxPrice, offset, limit);

            List<Product> products = es.ids().isEmpty()
                    ? Collections.emptyList()
                    : productDao.findByIds(es.ids()); // DB에서 실제 상품 정보 일괄 조회

            return new EsHit(products, es.suggestion(), es.total());

        } catch (IOException e) {
            log.error("Elasticsearch 검색 중 에러 발생", e);
            throw new RuntimeException("검색 시스템에 일시적인 오류가 발생했습니다.", e);
        }
    }
}
