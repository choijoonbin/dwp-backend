package com.dwp.core.event;

import java.util.concurrent.CompletableFuture;

/**
 * 이벤트 발행 인터페이스
 * 
 * Redis Pub/Sub, Kafka, RabbitMQ 등 다양한 메시징 시스템으로
 * 구현될 수 있도록 추상화된 인터페이스입니다.
 */
public interface EventPublisher {
    
    /**
     * 이벤트 발행 (동기)
     * 
     * @param event 발행할 도메인 이벤트
     */
    void publish(DomainEvent event);
    
    /**
     * 이벤트 발행 (비동기)
     * 
     * @param event 발행할 도메인 이벤트
     * @return 비동기 발행 결과
     */
    CompletableFuture<Void> publishAsync(DomainEvent event);
    
    /**
     * 특정 채널에 이벤트 발행
     * 
     * @param channel 발행할 채널명
     * @param event 발행할 도메인 이벤트
     */
    void publishToChannel(String channel, DomainEvent event);
}
