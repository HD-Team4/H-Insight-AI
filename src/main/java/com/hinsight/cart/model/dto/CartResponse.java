package com.hinsight.cart.model.dto;

import java.util.List;

public record CartResponse(
        List<CartItemResponse> items,
        int totalQuantity,
        long totalPrice
) {
    public static CartResponse of(List<CartItemResponse> items) {
        int totalQuantity = items.stream()
                .mapToInt(CartItemResponse::quantity)
                .sum();
        long totalPrice = items.stream()
                .mapToLong(CartItemResponse::lineTotal)
                .sum();
        return new CartResponse(items, totalQuantity, totalPrice);
    }
}