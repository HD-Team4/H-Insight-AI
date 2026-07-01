package com.hinsight.cart.model.dto;

import com.hinsight.cart.model.vo.Cart;
import com.hinsight.product.model.vo.Product;

public record CartItemResponse(
        Long cartId,
        Long productId,
        String productName,
        String imageUrl,
        Integer price,
        Integer quantity,
        Integer stockQuantity,
        long lineTotal
) {
    // Cart(수량) + Product(상품정보) 를 합쳐서 화면용 한 줄을 만든다
    public static CartItemResponse of(Cart cart, Product product) {
        long lineTotal = (long) product.getPrice() * cart.getQuantity();
        return new CartItemResponse(
                cart.getCartId(),
                product.getProductId(),
                product.getProductName(),
                product.getImageUrl(),
                product.getPrice(),
                cart.getQuantity(),
                product.getStockQuantity(),
                lineTotal
        );
    }
}