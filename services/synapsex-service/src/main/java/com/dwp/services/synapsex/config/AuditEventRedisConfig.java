package com.dwp.services.synapsex.config;

import com.dwp.services.synapsex.service.audit.AuditEventRedisSubscriber;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * Redis Pub/Sub 구독 설정.
 * 채널 audit:events:ingest에서 Aura 발행 AuditEvent 수신 → audit_event_log 저장.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "audit.redis.enabled", havingValue = "true", matchIfMissing = true)
public class AuditEventRedisConfig {

    @Value("${audit.redis.channel:audit:events:ingest}")
    private String auditChannel;

    @Bean
    public RedisMessageListenerContainer auditEventRedisListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter auditEventListenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(auditEventListenerAdapter, new ChannelTopic(auditChannel));
        log.info("AuditEvent Redis listener subscribed to channel: {}", auditChannel);
        return container;
    }

    @Bean
    public MessageListenerAdapter auditEventListenerAdapter(AuditEventRedisSubscriber subscriber) {
        return new MessageListenerAdapter(subscriber, "onMessage");
    }
}
