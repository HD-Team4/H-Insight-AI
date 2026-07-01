package com.hinsight.product.model.dto;

public record ProductSearchConditionDto(
        String keyword,
        Long categoryId,
        String categoryGroup,   // "TOP", "BOTTOM", "OUTER", "ACCESSORY"
        Integer minPrice,
        Integer maxPrice
) {
}
