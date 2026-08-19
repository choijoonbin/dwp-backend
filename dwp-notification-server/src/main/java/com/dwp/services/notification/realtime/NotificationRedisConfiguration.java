package com.dwp.services.notification.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "dwp.notification.realtime.redis-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class NotificationRedisConfiguration {

    @Bean
    RedisMessageListenerContainer notificationRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            NotificationRedisChannels channels,
            NotificationRedisSignalSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setRecoveryInterval(2_000L);
        container.addMessageListener(subscriber, channels.topics());
        return container;
    }
}
