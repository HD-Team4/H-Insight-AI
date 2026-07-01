package com.hinsight.order.model.dto;

import com.hinsight.order.model.vo.PurchaseHistory;
import com.hinsight.product.model.vo.Product;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PurchaseHistoryResponse(
        Long historyId,
        Long productId,
        String productName,
        String imageUrl,
        Integer quantity,
        BigDecimal price,
        BigDecimal lineTotal,
        LocalDateTime createdAt
) {
    // PurchaseHistory(구매기록) + Product(상품정보) 합쳐서 화면용 한 줄
    public static PurchaseHistoryResponse of(PurchaseHistory h, Product product) {
        BigDecimal lineTotal = h.getPrice().multiply(BigDecimal.valueOf(h.getQuantity()));
        return new PurchaseHistoryResponse(
                h.getHistoryId(),
                product.getProductId(),
                product.getProductName(),
                product.getImageUrl(),
                h.getQuantity(),
                h.getPrice(),
                lineTotal,
                h.getCreatedAt()
        );
    }
}
