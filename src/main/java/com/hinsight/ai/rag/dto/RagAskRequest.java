package com.hinsight.ai.rag.dto;

/**
 * 라이브 방송 리뷰 Q&A 요청.
 *
 * @param productId 방송 중인 상품 ID (필수)
 * @param question  방송 댓글에서 탐지된 질문 (필수)
 * @param cutoff    리뉴얼 기준일(ISO-8601). 이 날짜 이전 리뷰는 검색 대상에서 제외.
 *                  "2026-03-01" 또는 "2026-03-01T00:00:00+09:00" 형식. null/공백이면 필터 없음.
 */
public record RagAskRequest(
        Long productId,
        String question,
        String cutoff
) {
}
