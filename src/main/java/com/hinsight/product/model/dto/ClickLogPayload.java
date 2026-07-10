package com.hinsight.product.model.dto;

import lombok.Builder;

@Builder
public record ClickLogPayload(
        long productId,
        String productName,
        String brandName,
        Integer price
) {
}
