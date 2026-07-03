package com.hinsight.ai.vectorstore;

import java.math.BigDecimal;

/**
 * 벡터검색 결과 1건 (product_vectors + 코사인 유사도 점수).
 */
public record ProductMatch(
        Long productId,
        String name,
        String category,
        BigDecimal price,
        String color,
        String imageUrl,
        double score   // 1 - cosine_distance (1에 가까울수록 유사)
) {
}
