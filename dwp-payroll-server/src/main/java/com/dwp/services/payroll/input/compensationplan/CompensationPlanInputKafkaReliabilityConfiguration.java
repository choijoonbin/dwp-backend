package com.dwp.services.payroll.input.compensationplan;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

/** Bounded retry and deterministic DLT routing for the PAY-016 broker boundary. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "dwp.hris.payroll-compensation-plan-input.enabled",
        havingValue = "true",
        matchIfMissing = false)
class CompensationPlanInputKafkaReliabilityConfiguration {

    @Bean
    CommonErrorHandler compensationPlanInputErrorHandler(
            KafkaOperations<Object, Object> kafka,
            @Value("${dwp.hris.payroll-compensation-plan-input.broker.retry-delay:PT2S}")
            Duration retryDelay,
            @Value("${dwp.hris.payroll-compensation-plan-input.broker.retry-attempts:5}")
            int retryAttempts) {
        if (retryDelay.isNegative() || retryDelay.isZero()
                || retryDelay.compareTo(Duration.ofMinutes(1)) > 0
                || retryAttempts < 1 || retryAttempts > 20) {
            throw new IllegalStateException(
                    "PAY-016 broker retry policy is outside its bounded range.");
        }
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafka,
                (record, failure) -> new TopicPartition(
                        CompensationPlanInputContracts.DEAD_LETTER_TOPIC,
                        record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(retryDelay.toMillis(), retryAttempts - 1L));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        handler.setCommitRecovered(true);
        return handler;
    }
}
