package com.dwp.services.notification.integration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "dwp.notification.domain-events.enabled",
        havingValue = "true")
public class NotificationDomainEventKafkaConfiguration {

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String>
            notificationDomainEventKafkaListenerContainerFactory(
                    ConsumerFactory<String, String> consumerFactory,
                    KafkaTemplate<Object, Object> kafkaTemplate,
                    @Value("${dwp.notification.domain-events.dead-letter-topic:"
                            + "dwp.domain-events.v1.DLT}") String deadLetterTopic,
                    @Value("${dwp.notification.domain-events.retry-interval:1s}")
                    Duration retryInterval,
                    @Value("${dwp.notification.domain-events.maximum-attempts:4}")
                    int maximumAttempts) {
        if (deadLetterTopic == null || deadLetterTopic.isBlank()) {
            throw new IllegalArgumentException("Notification domain-event DLT is required.");
        }
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException(
                    "Notification domain-event maximum attempts must be at least 1.");
        }
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception exception) -> new TopicPartition(
                        deadLetterTopic.trim(), record.partition()));
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(retryInterval.toMillis(), maximumAttempts - 1L));
        errorHandler.addNotRetryableExceptions(NotificationDomainEventException.class);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
