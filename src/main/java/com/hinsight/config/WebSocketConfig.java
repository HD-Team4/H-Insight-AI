package com.hinsight.config;

import com.hinsight.live.config.LiveChatHandshakeHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * 라이브 방 N:N 실시간 채팅용 STOMP 설정.
 * 단일 인스턴스라 별도 메시지 브로커 없이 내장 SimpleBroker 로 브로드캐스트한다.
 * (다중 인스턴스로 확장 시 이 브로커를 Redis/RabbitMQ 릴레이로 교체)
 *
 * - 클라이언트 연결:   /ws-live (SockJS 폴백 허용)
 * - 구독(수신):        /topic/live/{liveSessionId}
 * - 발행(송신):        /app/live/{liveSessionId}/chat
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-live")
                .setHandshakeHandler(new LiveChatHandshakeHandler())
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");      // 서버 → 구독자 브로드캐스트
        registry.setApplicationDestinationPrefixes("/app"); // 클라이언트 → @MessageMapping
    }
}
