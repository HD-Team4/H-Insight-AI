package com.hinsight.chatbot.model.dto;

/**
 * Gemini 가 사용자 질문에서 추출한 구조화 조건.
 * gender/subcat/minPrice/maxPrice 는 SQL 필터, semanticText 는 BGE-M3 임베딩 대상.
 */
public record ParsedQuery(
        String gender,       // "남성" / "여성" / null
        String subcat,       // product_vectors.subcat 값 중 하나 / null
        Integer minPrice,    // 원 / null
        Integer maxPrice,    // 원 / null
        String semanticText  // 색상·소재·느낌·용도 등 의미검색 구절
) {
}
