package com.hinsight.product.model.dto;

public record ProductDetailDto(
        Long productId,
        String brandName,
        String productName,
        Integer price,
        Integer stockQuantity,
        String imageUrl,
        String description,
        String productInfo
) {
}
