package com.hinsight.product.model.dto;

public record ProductSearchConditionDto (
        String keyword,
        Long categoryId,
        Integer minPrice,
        Integer maxPrice
) {
}
