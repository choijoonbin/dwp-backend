package com.dwp.services.notification.integration;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "dwp.notification.approval-pilot.enabled",
        havingValue = "true")
public class ApprovalNotificationKafkaConfiguration {

    @Bean
    DefaultErrorHandler notificationApprovalKafkaErrorHandler(
            KafkaTemplate<Object, Object> kafkaTemplate,
            @Value("${dwp.notification.approval-pilot.dead-letter-topic:"
                    + "dwp.approval.events.v1.DLT}") String deadLetterTopic,
            @Value("${dwp.notification.approval-pilot.retry-interval:1s}")
            Duration retryInterval,
            @Value("${dwp.notification.approval-pilot.maximum-attempts:4}")
            int maximumAttempts) {
        if (deadLetterTopic == null || deadLetterTopic.isBlank()) {
            throw new IllegalArgumentException("Approval notification DLT must be configured.");
        }
        long retryCount = retryCount(maximumAttempts);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(
                        deadLetterTopic.trim(), record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(retryInterval.toMillis(), retryCount));
        handler.addNotRetryableExceptions(ApprovalNotificationEventException.class);
        return handler;
    }

    static long retryCount(int maximumAttempts) {
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException(
                    "Approval notification maximum attempts must be at least 1.");
        }
        return maximumAttempts - 1L;
    }
}
