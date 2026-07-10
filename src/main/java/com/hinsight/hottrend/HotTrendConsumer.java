package com.hinsight.hottrend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * activity.click / activity.purchase 토픽을 실시간 소비해 급상승 랭킹(Redis)에 반영한다.
 * <p>S3 배치 파이프라인(Kafka Connect S3 Sink)과 <b>독립된 컨슈머 그룹</b>이다 —
 * 같은 이벤트 스트림을 배치 레이어(정확·히스토리)와 스피드 레이어(실시간·근사)가 각각 소비.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotTrendConsumer {

    private final HotTrendService hotTrend;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = {"activity.click", "activity.purchase"}, groupId = "hinsight-hot-trend")
    public void consume(String message) {
        try {
            JsonNode e = objectMapper.readTree(message);
            String type = e.path("type").asText("");
            JsonNode p = e.path("payload");
            long productId = p.path("productId").asLong(0);
            if (productId <= 0) {
                return;
            }
            String name = p.path("productName").asText(null);
            if ("click".equals(type)) {
                hotTrend.hit("view", productId, 1, name);                       // 조회량
            } else if ("purchase".equals(type)) {
                hotTrend.hit("sale", productId, Math.max(1, p.path("quantity").asInt(1)), name);  // 판매량(수량)
            }
        } catch (Exception ex) {
            log.debug("[HotTrend] 이벤트 파싱 실패(무시): {}", ex.getMessage());
        }
    }
}
