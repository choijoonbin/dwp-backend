package com.dwp.services.payroll.input.compensationplan;

import com.dwp.core.event.DomainEventEnvelope;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Thin broker adapter; all durable semantics live in the PAY-owned consumer. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.payroll-compensation-plan-input.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class CompensationPlanInputBrokerConsumer {

    private final CompensationPlanInputEventHandler handler;
    private final ObjectMapper mapper;

    CompensationPlanInputBrokerConsumer(
            CompensationPlanInputEventHandler handler,
            ObjectMapper objectMapper) {
        this.handler = handler;
        this.mapper = objectMapper.copy().findAndRegisterModules()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @KafkaListener(
            topics = CompensationPlanInputContracts.TOPIC,
            groupId = "${dwp.hris.payroll-compensation-plan-input.consumer-group:"
                    + "dwp-payroll-compensation-plan-input-v1}")
    void accept(String payload) {
        try {
            handler.consume(mapper.readValue(payload, DomainEventEnvelope.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "PAY-016 broker envelope is not canonical JSON.", exception);
        }
    }
}
