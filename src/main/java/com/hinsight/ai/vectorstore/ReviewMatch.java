package com.hinsight.ai.vectorstore;

import java.time.OffsetDateTime;

/**
 * review_vectors 검색 결과 1건.
 * official=true 면 제조사 공식 스펙(리뉴얼 안내), false 면 유저 리뷰.
 * finalScore = wSim*sim + wRec*recency (공식 스펙 트랙은 별도 확보되므로 finalScore 미사용, 0).
 */
public record ReviewMatch(
        long id,
        Long productId,
        String content,
        boolean official,
        OffsetDateTime writtenAt,
        double sim,          // cosine_distance (1에 가까울수록 유사)
        double recency,      // 0.5^(age_days/halfLife), 0~1
        double finalScore
) {
}
