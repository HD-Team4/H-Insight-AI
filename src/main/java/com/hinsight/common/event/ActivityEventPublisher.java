package com.hinsight.common.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 행동 로그를 Kafka로 내보내는 공용 발행기.
 * 각 기능은 KafkaTemplate 을 직접 쓰지 않고 이 컴포넌트만 호출한다.
 *
 * <p>분석용 로그라 실패는 흐름을 막지 않고 로깅만 한다(fire-and-forget). 원천 데이터는 DB에 이미 안전.
 * 메시지 key 는 userId(파티션 내 유저별 순서 보장), value 는 봉투 JSON.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /** topic 예: "activity.purchase", "activity.search" */
    public void publish(String topic, ActivityEvent event) {
        final String value;
        try {
            value = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("[activity] 직렬화 실패 topic={} type={}", topic, event.type(), e);
            return;
        }

        String key = (event.userId() == null) ? null : String.valueOf(event.userId());
        kafkaTemplate.send(topic, key, value).whenComplete((res, ex) -> {
            if (ex != null) {
                log.warn("[activity] 발행 실패 topic={} type={}: {}", topic, event.type(), ex.getMessage());
            }
        });
    }
}
