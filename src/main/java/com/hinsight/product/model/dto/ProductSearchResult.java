package com.hinsight.product.model.dto;

import com.hinsight.product.model.vo.Product;

import java.util.List;

/**
 * 상품 검색 결과 + 오타 교정(did-you-mean) 정보.
 * corrected=true 이면 originalKeyword 검색 결과가 없어 correctedKeyword 로 재검색한 결과다.
 */
public record ProductSearchResult(
        List<Product> products,
        String originalKeyword,
        String correctedKeyword,
        boolean corrected
) {
    public static ProductSearchResult of(List<Product> products) {
        return new ProductSearchResult(products, null, null, false);
    }

    public static ProductSearchResult corrected(List<Product> products, String original, String corrected) {
        return new ProductSearchResult(products, original, corrected, true);
    }
}
