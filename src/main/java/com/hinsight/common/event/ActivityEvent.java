package com.hinsight.common.event;

import java.time.Instant;

/**
 * 데이터레이크(Kafka→S3)로 흘려보내는 행동 로그 공통 봉투(envelope).
 * type 별로 서로 다른 토픽(activity.&lt;type&gt;)에 적재된다.
 * payload 는 이벤트별 상세(구매/검색 등)로, JSON 직렬화되어 그대로 저장된다.
 */
public record ActivityEvent(String type, Long userId, Instant occurredAt, Object payload) {

    public static ActivityEvent of(String type, Long userId, Object payload) {
        return new ActivityEvent(type, userId, Instant.now(), payload);
    }
}
