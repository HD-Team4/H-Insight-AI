package com.hinsight.product.model.dto;

import lombok.Builder;

@Builder
public record SearchLogPayload(
        String keyword,
        int page,
        long resultCount
) {
}
