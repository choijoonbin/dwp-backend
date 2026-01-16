package com.dwp.core.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Redis Pub/Sub 기반 이벤트 발행 구현체
 * 
 * 주요 채널:
 * - dwp:events:all - 모든 이벤트 (Aura-Platform이 구독)
 * - dwp:events:{service} - 서비스별 이벤트
 * - dwp:events:{tenant} - 테넌트별 이벤트
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisEventPublisher implements EventPublisher {
    
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    
    // 기본 채널: 모든 이벤트가 발행되는 채널
    private static final String DEFAULT_CHANNEL = "dwp:events:all";
    
    @Override
    @SuppressWarnings("null")
    public void publish(DomainEvent event) {
        try {
            // 이벤트 ID가 없으면 생성
            if (event.getEventId() == null || event.getEventId().isEmpty()) {
                event.setEventId(UUID.randomUUID().toString());
            }
            
            // 타임스탬프가 없으면 생성
            if (event.getTimestamp() == null) {
                event.setTimestamp(LocalDateTime.now());
            }
            
            // JSON 직렬화
            String eventJson = objectMapper.writeValueAsString(event);
            
            // Redis Pub/Sub으로 발행
            redisTemplate.convertAndSend(DEFAULT_CHANNEL, eventJson);
            
            String eventId = Objects.requireNonNullElse(event.getEventId(), "unknown");
            String eventType = Objects.requireNonNullElse(event.getEventType(), "unknown");
            log.debug("Published event to Redis: eventId={}, type={}, channel={}", 
                     eventId, eventType, DEFAULT_CHANNEL);
            
        } catch (JsonProcessingException e) {
            String eventId = Objects.requireNonNullElse(event.getEventId(), "unknown");
            String eventType = Objects.requireNonNullElse(event.getEventType(), "unknown");
            log.error("Failed to serialize event: eventId={}, type={}", 
                     eventId, eventType, e);
            throw new RuntimeException("Failed to publish event", e);
        }
    }
    
    @Async
    @Override
    public CompletableFuture<Void> publishAsync(DomainEvent event) {
        return CompletableFuture.runAsync(() -> publish(event));
    }
    
    @Override
    @SuppressWarnings("null")
    public void publishToChannel(String channel, DomainEvent event) {
        try {
            // 이벤트 ID가 없으면 생성
            if (event.getEventId() == null || event.getEventId().isEmpty()) {
                event.setEventId(UUID.randomUUID().toString());
            }
            
            // 타임스탬프가 없으면 생성
            if (event.getTimestamp() == null) {
                event.setTimestamp(LocalDateTime.now());
            }
            
            // JSON 직렬화
            String eventJson = objectMapper.writeValueAsString(event);
            
            // 지정된 채널에 발행 (null 체크)
            String channelToUse = (channel != null && !channel.isEmpty()) ? channel : DEFAULT_CHANNEL;
            redisTemplate.convertAndSend(channelToUse, eventJson);
            
            String eventId = Objects.requireNonNullElse(event.getEventId(), "unknown");
            String eventType = Objects.requireNonNullElse(event.getEventType(), "unknown");
            log.debug("Published event to Redis: eventId={}, type={}, channel={}", 
                     eventId, eventType, channel);
            
        } catch (JsonProcessingException e) {
            String eventId = Objects.requireNonNullElse(event.getEventId(), "unknown");
            String eventType = Objects.requireNonNullElse(event.getEventType(), "unknown");
            log.error("Failed to serialize event: eventId={}, type={}, channel={}", 
                     eventId, eventType, channel, e);
            throw new RuntimeException("Failed to publish event to channel: " + channel, e);
        }
    }
}
