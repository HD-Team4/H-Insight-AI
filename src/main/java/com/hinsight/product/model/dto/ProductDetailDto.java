package com.hinsight.product.model.dto;

public record ProductDetailDto(
        Long productId,
        String brandName,
        String productName,
        Integer price,
        String imageUrl,
        String description,
        String productInfo
) {
}
