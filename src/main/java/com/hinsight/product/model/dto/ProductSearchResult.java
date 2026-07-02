package com.hinsight.product.model.dto;

import com.hinsight.product.model.vo.Product;

import java.util.List;

/**
 * 상품 검색 결과 + 오타 교정(did-you-mean) 정보 + 페이지네이션 정보.
 * corrected=true 이면 originalKeyword 검색 결과가 없어 correctedKeyword 로 재검색한 결과다.
 */
public record ProductSearchResult(
        List<Product> products,
        String originalKeyword,
        String correctedKeyword,
        boolean corrected,
        PageInfo page
) {
    public static ProductSearchResult of(List<Product> products, PageInfo page) {
        return new ProductSearchResult(products, null, null, false, page);
    }

    public static ProductSearchResult corrected(List<Product> products, String original, String corrected, PageInfo page) {
        return new ProductSearchResult(products, original, corrected, true, page);
    }
}
