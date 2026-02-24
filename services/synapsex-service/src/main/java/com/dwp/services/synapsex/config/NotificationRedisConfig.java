package com.dwp.services.synapsex.config;

import com.dwp.services.synapsex.service.notification.NotificationRedisSubscriber;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * 알림 브리지: Aura가 발행하는 workbench:* 채널을 패턴 구독(PSUBSCRIBE)하여 상시 수신.
 * workbench:case:action 포함 — FE 알림은 Redis 수신 후 /topic/notifications 로 브로드캐스트.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "notification.redis.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationRedisConfig {

    @Value("${notification.redis.workbench-pattern:workbench:*}")
    private String workbenchPattern;

    @Bean
    public RedisMessageListenerContainer notificationRedisListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter notificationListenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(notificationListenerAdapter, new PatternTopic(workbenchPattern));
        log.info("Notification Redis listener subscribed to pattern: {} (includes workbench:case:action)", workbenchPattern);
        return container;
    }

    @Bean
    public MessageListenerAdapter notificationListenerAdapter(NotificationRedisSubscriber subscriber) {
        return new MessageListenerAdapter(subscriber, "onMessage");
    }
}
