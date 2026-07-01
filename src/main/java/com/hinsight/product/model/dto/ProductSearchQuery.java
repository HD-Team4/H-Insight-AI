package com.hinsight.product.model.dto;
import java.util.List;

public record ProductSearchQuery(
        String keyword,
        List<Long> categoryIds,
        Integer minPrice,
        Integer maxPrice
) {
}
