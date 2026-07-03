package com.hinsight.chatbot.model.dto;

import com.hinsight.ai.vectorstore.ProductMatch;

import java.util.List;

/**
 * SSE 스트림의 첫 이벤트(head): 조건 해석 결과 + 추천 상품 목록.
 * 임베딩·벡터검색(빠른 구간)이 끝나는 즉시 내려주고, 추천 멘트(reply)는 뒤이어 delta 로 흘린다.
 */
public record ChatbotHead(
        ParsedQuery parsed,
        List<ProductMatch> products
) {
}
