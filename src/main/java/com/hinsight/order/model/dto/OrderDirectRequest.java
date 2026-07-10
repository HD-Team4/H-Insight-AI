package com.hinsight.order.model.dto;

public record OrderDirectRequest(
        Long productId,
        Integer quantity
) {
}
