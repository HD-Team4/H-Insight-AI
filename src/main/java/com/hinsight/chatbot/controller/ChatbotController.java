package com.hinsight.chatbot.controller;

import com.hinsight.chatbot.model.dto.AskRequest;
import com.hinsight.chatbot.model.dto.ChatbotHead;
import com.hinsight.chatbot.service.ChatbotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * 상품추천 챗봇 (일반고객 전용 — /customer/** 는 ROLE_CUSTOMER 필요).
 * 추천 응답은 SSE 스트림: 먼저 head(조건+상품), 이어서 추천멘트 토큰(delta), 마지막 done.
 */
@Tag(name = "chatbot-controller", description = "챗봇 컨트롤러")
@Controller
@RequiredArgsConstructor
@RequestMapping("/customer/chatbot")
public class ChatbotController {

    private final ChatbotService chatbotService;

    // 챗봇 페이지
    @GetMapping
    public String chatPage() {
        return "customer/chatbot/chat";
    }

    /**
     * 질문 → 추천 (SSE 스트리밍).
     * 이벤트: head(ChatbotHead JSON) → delta({"t":토큰}) 여러 개 → done({}).
     */
    @ResponseBody
    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> ask(@RequestBody AskRequest request) {
        String message = request.message();

        return Flux.defer(() -> {
            // 빠른 구간(임베딩+벡터검색)을 먼저 끝내 head 로 즉시 전달
            ChatbotHead head = chatbotService.search(message);

            Flux<ServerSentEvent<Object>> headEvent = Flux.just(
                    ServerSentEvent.<Object>builder(head).event("head").build());

            Flux<ServerSentEvent<Object>> deltaEvents = chatbotService.streamReply(message, head)
                    .map(token -> ServerSentEvent.<Object>builder(Map.of("t", token)).event("delta").build());

            Flux<ServerSentEvent<Object>> doneEvent = Flux.just(
                    ServerSentEvent.<Object>builder(Map.of()).event("done").build());

            return Flux.concat(headEvent, deltaEvents, doneEvent);
        }).subscribeOn(Schedulers.boundedElastic());  // 블로킹 검색을 요청 스레드 밖에서 수행
    }
}
