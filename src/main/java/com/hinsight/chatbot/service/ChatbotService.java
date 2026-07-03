package com.hinsight.chatbot.service;

import com.hinsight.ai.embedding.EmbeddingService;
import com.hinsight.ai.vectorstore.ProductMatch;
import com.hinsight.ai.vectorstore.VectorSearchService;
import com.hinsight.chatbot.model.dto.ChatbotHead;
import com.hinsight.chatbot.model.dto.ParsedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 상품추천 챗봇 코어 파이프라인.
 * 질문 → Gemini 조건추출 → BGE-M3(Ollama) 임베딩 → pgvector 필터+코사인 검색 (여기까지 {@link #search})
 *      → Gemini 추천멘트 스트리밍 ({@link #streamReply}).
 * 검색 결과(head)를 먼저 내려주고 추천멘트는 뒤이어 토큰 스트림으로 흘리는 SSE 구조.
 */
@Service
@RequiredArgsConstructor
public class ChatbotService {

    private final QueryConditionExtractor extractor;
    private final EmbeddingService embeddingService;
    private final VectorSearchService vectorSearchService;
    private final RecommendationWriter recommendationWriter;

    private static final int TOP_K = 5;

    /** 조건추출 + 임베딩 + 벡터검색까지의 (블로킹) 빠른 구간. */
    public ChatbotHead search(String message) {
        ParsedQuery pq = extractor.extract(message);

        // 의미텍스트가 비면 원문으로 임베딩
        String semantic = StringUtils.hasText(pq.semanticText()) ? pq.semanticText() : message;
        float[] queryVector = embeddingService.embed(semantic);

        List<ProductMatch> products = vectorSearchService.search(
                pq.gender(), pq.subcat(), pq.minPrice(), pq.maxPrice(), queryVector, TOP_K);

        return new ChatbotHead(pq, products);
    }

    /** 검색 결과에 대한 추천 멘트를 토큰 스트림으로 생성. */
    public Flux<String> streamReply(String message, ChatbotHead head) {
        return recommendationWriter.stream(message, head.parsed(), head.products());
    }
}
