package com.dwp.services.platform.hrisconfiguration.operations;

import com.dwp.core.event.DomainEventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

import static com.dwp.services.platform.hrisconfiguration.operations.HrisOperationsModels.*;

@Component
@ConditionalOnProperty(
        name = "dwp.hris.operations-exception-center.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class HrisOperationalExceptionEventConsumer {

    private final HrisOperationalExceptionEventContract contract;
    private final HrisOperationalExceptionRepository repository;
    private final HrisOperationsRuntimeControls controls;
    private final ObjectProvider<KafkaTemplate<String, String>> kafka;

    HrisOperationalExceptionEventConsumer(
            HrisOperationalExceptionEventContract contract,
            HrisOperationalExceptionRepository repository,
            HrisOperationsRuntimeControls controls,
            ObjectProvider<KafkaTemplate<String, String>> kafka) {
        this.contract = contract;
        this.repository = repository;
        this.controls = controls;
        this.kafka = kafka;
    }

    @KafkaListener(
            topics = "dwp.domain-events.v1",
            groupId = HrisOperationalExceptionEventContract.GROUP,
            properties = {
                    "key.deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                    "value.deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer"
            })
    void onMessage(ConsumerRecord<String, byte[]> record) {
        byte[] bytes = record.value();
        if (!contract.supports(bytes)) {
            return;
        }
        DomainEventEnvelope envelope = contract.parseEnvelope(bytes);
        contract.requirePartitionKey(record.key(), envelope);
        IngressResult result = consume(envelope);
        if (result.state() == IngressState.DEFERRED) {
            throw new IllegalStateException(
                    "SYS-EVT-009 requires ordered transport redelivery");
        }
    }

    IngressResult consume(DomainEventEnvelope event) {
        if (!contract.supports(event)) {
            return IngressResult.of(IngressState.IGNORED_UNRELATED);
        }
        if (event.tenantId() == null || event.tenantId() <= 0) {
            throw new IllegalArgumentException("Invalid SYS-EVT-009 envelope");
        }
        if (!controls.enrolled(event.tenantId())) {
            return IngressResult.of(IngressState.IGNORED_NOT_ENROLLED);
        }
        ValidatedEvent validated = contract.validate(event);
        IngressResult result = repository.apply(validated);
        if (result.state() == IngressState.DEAD_LETTER_REQUIRED
                || result.state() == IngressState.QUARANTINED) {
            deadLetter(validated);
        }
        return result;
    }

    private void deadLetter(ValidatedEvent event) {
        KafkaTemplate<String, String> template = kafka.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException(
                    "SYS-EVT-009 dead-letter transport is unavailable");
        }
        String key = event.envelope().tenantId() + "|"
                + event.envelope().source() + "|"
                + event.envelope().aggregateType() + "|"
                + event.envelope().aggregateId();
        try {
            template.send("dwp.domain-events.v1.DLT", key,
                            event.canonicalPayload())
                    .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "SYS-EVT-009 dead-letter publication failed", exception);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "SYS-EVT-009 dead-letter publication failed", exception);
        }
    }
}
