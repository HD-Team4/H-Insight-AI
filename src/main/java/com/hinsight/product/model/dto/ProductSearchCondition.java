package com.hinsight.product.model.dto;

public record ProductSearchCondition(
        String keyword,
        Long categoryId,
        String categoryGroup,   // "TOP", "BOTTOM", "OUTER", "ACCESSORY"
        Integer minPrice,
        Integer maxPrice,
        Integer page            // 1-based, null 이면 1페이지
) {
}
