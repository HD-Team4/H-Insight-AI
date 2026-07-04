package com.hinsight.ai.rag;

import com.hinsight.ai.rag.dto.RagAskRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * 라이브 방송 리뷰 기반 Q&A 챗봇 (KAN-103).
 * 방송 댓글에서 탐지된 질문을 받아 리뷰/공식 스펙 RAG 답변을 SSE 로 스트리밍한다.
 * 이벤트: delta({"t":토큰}) 여러 개 → done({}).
 */
@Tag(name = "rag-controller", description = "라이브 리뷰 Q&A 챗봇")
@RestController
@RequiredArgsConstructor
@RequestMapping("/customer/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RagService ragService;

    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> ask(@RequestBody RagAskRequest request) {
        if (request.productId() == null || !StringUtils.hasText(request.question())) {
            return Flux.just(ServerSentEvent.<Object>builder(
                    Map.of("message", "productId 와 question 은 필수입니다.")).event("error").build());
        }

        OffsetDateTime cutoff = parseCutoff(request.cutoff());

        return Flux.defer(() -> {
            Flux<ServerSentEvent<Object>> deltaEvents =
                    ragService.answer(request.productId(), request.question(), cutoff)
                            .map(token -> ServerSentEvent.<Object>builder(Map.of("t", token))
                                    .event("delta").build());

            Flux<ServerSentEvent<Object>> doneEvent = Flux.just(
                    ServerSentEvent.<Object>builder(Map.of()).event("done").build());

            return Flux.concat(deltaEvents, doneEvent);
        }).subscribeOn(Schedulers.boundedElastic());  // 블로킹 임베딩/검색을 요청 스레드 밖에서 수행
    }

    /** "2026-03-01" 또는 ISO offset datetime 을 허용. 파싱 실패/공백이면 null(필터 없음). */
    private OffsetDateTime parseCutoff(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        String s = raw.trim();
        try {
            return OffsetDateTime.parse(s);
        } catch (Exception ignore) {
            // 날짜만 온 경우 KST 자정으로 해석
        }
        try {
            return LocalDate.parse(s).atStartOfDay(KST).toOffsetDateTime();
        } catch (Exception e) {
            log.warn("[RAG] cutoff 파싱 실패, 필터 없이 진행: {}", raw);
            return null;
        }
    }
}
