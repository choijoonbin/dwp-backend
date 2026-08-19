package com.dwp.services.messaging.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "dwp.messaging.realtime.redis-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MessagingRedisConfiguration {

    @Bean
    RedisMessageListenerContainer messagingRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessagingRedisChannels channels,
            MessagingRedisSignalSubscriber subscriber,
            MessagingTypingRedisSubscriber typingSubscriber) {
        RedisMessageListenerContainer container =
                new MessagingResilientRedisMessageListenerContainer(2_000L);
        container.setConnectionFactory(connectionFactory);
        container.setRecoveryInterval(2_000L);
        container.addMessageListener(subscriber, channels.topics());
        container.addMessageListener(typingSubscriber, channels.typingTopics());
        return container;
    }
}
