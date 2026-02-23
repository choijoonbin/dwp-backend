package com.dwp.services.synapsex.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket 알림 브리지: /ws/notifications 엔드포인트, /topic/notifications 브로드캐스트.
 * SockJS: disconnectDelay로 유휴 세션 유지 시간 조정 (기본 ~5분 → 30분).
 * STOMP/SockJS HandlerMapping을 MVC보다 우선시키기 위해 setOrder(Ordered.HIGHEST_PRECEDENCE) 적용.
 * (그렇지 않으면 ws notifications eventsource 경로가 MVC에 매칭되어 LinkedHashMap 반환 후 converter 오류 발생)
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${notification.websocket.allowed-origins:*}")
    private String allowedOrigins;

    /** 유휴 시 세션 종료까지 대기 시간(ms). 기본 30분. */
    @Value("${notification.websocket.disconnect-delay-ms:1800000}")
    private long disconnectDelayMs;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registry.addEndpoint("/ws/notifications")
                .setAllowedOriginPatterns(allowedOrigins != null && !allowedOrigins.isBlank() ? allowedOrigins.split(",\\s*") : new String[]{"*"})
                .withSockJS()
                .setDisconnectDelay(disconnectDelayMs > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) disconnectDelayMs);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }
}
