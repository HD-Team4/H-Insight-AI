package com.hinsight.order.model.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PurchaseHistory {

    private Long historyId;          // history_id
    private Long userId;             // user_id
    private Long productId;          // product_id
    private Integer quantity;        // quantity
    private BigDecimal price;        // price (구매 당시 가격 스냅샷)
    private LocalDateTime createdAt; // created_at

}