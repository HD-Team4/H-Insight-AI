package com.hinsight.common.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 행동 로그(activity.*) 토픽을 앱 기동 시 선언적으로 생성한다.
 * spring-kafka 가 자동 구성하는 KafkaAdmin 이 이 NewTopic 빈들을 브로커에 반영한다
 * (이미 있으면 그대로 둠). 미리 만들어 두어 S3 Sink(regex 구독)의 첫 토픽 인식 지연을 없앤다.
 *
 * <p>파티션/복제 수는 프로퍼티로 조정 — 로컬(단일 브로커)은 replicas=1, 운영은 다중 브로커에 맞춰 override.
 * Kafka 미기동 환경(예: ES만 띄운 EC2)에서도 KafkaAdmin 은 fail-fast 하지 않아 앱 기동을 막지 않는다.
 */
@Configuration
public class KafkaTopicConfig {

    @Value("${app.kafka.activity.partitions:3}")
    private int partitions;

    @Value("${app.kafka.activity.replicas:1}")
    private short replicas;

    @Bean
    public NewTopic activityPurchaseTopic() {
        return TopicBuilder.name("activity.purchase")
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }

    @Bean
    public NewTopic activitySearchTopic() {
        return TopicBuilder.name("activity.search")
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }

    @Bean
    public NewTopic activityClickTopic() {
        return TopicBuilder.name("activity.click")
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }

    // 이벤트 타입 추가 시 여기 @Bean 한 개씩.
}
