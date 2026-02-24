package com.dwp.services.synapsex.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import lombok.extern.slf4j.Slf4j;

/**
 * WebSocket 알림 브리지: /ws/notifications 엔드포인트, /topic/notifications 브로드캐스트.
 * SockJS: disconnectDelay로 유휴 세션 유지 시간 조정 (기본 ~5분 → 30분).
 * STOMP/SockJS HandlerMapping을 MVC보다 우선시키기 위해 setOrder(Ordered.HIGHEST_PRECEDENCE) 적용.
 * (그렇지 않으면 ws notifications eventsource 경로가 MVC에 매칭되어 LinkedHashMap 반환 후 converter 오류 발생)
 */
@Slf4j
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

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
                StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
                StompCommand command = accessor.getCommand();
                if (command != null) {
                    String sessionId = accessor.getSessionId();
                    switch (command) {
                        case CONNECT -> {
                            boolean hasAuth = accessor.getNativeHeader("Authorization") != null;
                            log.info("STOMP CONNECT allowed sessionId={} principal={} hasAuthorization={} (Token 검증 실패 시 여기서 거절하지 않음 — 연결 유지, FE에서 tenantId 필터링)",
                                    sessionId, accessor.getUser(), hasAuth);
                        }
                        case SUBSCRIBE -> log.debug("STOMP SUBSCRIBE sessionId={} destination={}", sessionId, accessor.getDestination());
                        case DISCONNECT -> log.debug("STOMP DISCONNECT sessionId={} (즉시 종료 원인 확인 시 CONNECT/afterSendCompletion 로그 선행 확인)", sessionId);
                        default -> { }
                    }
                }
                return message;
            }

            @Override
            public void afterSendCompletion(@NonNull Message<?> message, @NonNull MessageChannel channel, boolean sent, @Nullable Exception ex) {
                if (ex != null) {
                    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
                    log.warn("STOMP afterSendCompletion error command={} sessionId={} (Token 검증 실패로 DISCONNECT 가능성 — 모니터링 시 이 로그와 DISCONNECT 연관 확인): {}",
                            accessor.getCommand(), accessor.getSessionId(), ex.getMessage());
                }
            }
        });
    }
}
