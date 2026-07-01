package com.hinsight.order.model.dto;

import java.math.BigDecimal;
import java.util.List;

public record OrderResponse(
        List<PurchaseHistoryResponse> items,
        BigDecimal totalPrice
) {
    public static OrderResponse of(List<PurchaseHistoryResponse> items) {
        BigDecimal totalPrice = items.stream()
                .map(PurchaseHistoryResponse::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new OrderResponse(items, totalPrice);
    }
}