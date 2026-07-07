package com.hinsight.chatbot.service;

import com.hinsight.ai.vectorstore.ProductMatch;
import com.hinsight.chatbot.model.dto.ParsedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 검색된 추천 상품들에 대해 Gemini 로 자연스러운 추천 멘트(추천 이유)를 스트리밍 생성한다.
 * SSE 로 토큰을 흘려 타이핑되듯 보여준다. LLM 호출이 실패해도 챗봇이 죽지 않도록 템플릿 문구로 폴백한다.
 */
@Service
public class RecommendationWriter {

    private static final Logger log = LoggerFactory.getLogger(RecommendationWriter.class);

    private final ChatClient chatClient;

    public RecommendationWriter(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    private static final String SYSTEM = """
            너는 의류 쇼핑몰의 친근한 상품 추천 도우미다.
            아래 '추천 상품 목록'만 근거로, 고객의 요청에 왜 이 상품들이 어울리는지 따뜻하게 설명하라.
            규칙:
            - 목록에 없는 상품/기능/재고/할인/배송 등은 절대 지어내지 말 것.
            - 상위 1~2개 상품은 이름을 그대로 언급해도 좋다.
            - 2~3문장, 200자 이내. 존댓말. 마크다운/목록/코드블록/이모지 금지.
            """;

    /**
     * 추천 멘트를 토큰 단위 스트림으로 생성한다.
     *
     * @param userMessage 고객 원문 질문
     * @param pq          추출된 조건(현재는 프롬프트에 직접 안 넣지만, 추후 톤 조정 여지용)
     * @param products    벡터검색으로 선별된 추천 상품
     * @return 추천 멘트 토큰 Flux (실패 시 템플릿 문구 1건으로 폴백)
     */
    public Flux<String> stream(String userMessage, ParsedQuery pq, List<ProductMatch> products) {
        if (products == null || products.isEmpty()) {
            return Flux.just("조건에 딱 맞는 상품을 찾지 못했어요. 가격대나 키워드를 조금 바꿔서 다시 말씀해 주세요.");
        }

        String productLines = products.stream()
                .map(p -> "- " + p.name() + " (" + p.category() + ", " + p.price().longValue() + "원)")
                .collect(Collectors.joining("\n"));

        String user = """
                고객 요청: %s

                추천 상품 목록:
                %s
                """.formatted(userMessage, productLines);

        return chatClient.prompt()
                .system(SYSTEM)
                .user(user)
                .stream()
                .content()
                // 쿼터 소진(429) 시 SDK 가 구독 스레드를 잡고 내부 재시도(수십 초)를 함
                //  → subscribeOn 으로 블로킹을 워커로 분리해야 timeout 타이머가 즉시 시작된다(실측 42초→8초).
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .timeout(java.time.Duration.ofSeconds(8))
                .onErrorResume(e -> {
                    log.warn("[챗봇] 추천멘트 생성 실패, 템플릿 폴백: {} ({})", userMessage, e.getMessage());
                    return Flux.just(fallback(products));
                });
    }

    private String fallback(List<ProductMatch> products) {
        return "요청하신 조건에 가까운 상품 " + products.size() + "개를 찾았어요. 마음에 드는 상품을 골라보세요.";
    }
}
