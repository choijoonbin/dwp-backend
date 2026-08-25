package com.dwp.services.provider.rollout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaFeatureRolloutDecisionEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafka;

    private ObjectMapper objectMapper;
    private KafkaFeatureRolloutDecisionEventPublisher publisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        publisher = new KafkaFeatureRolloutDecisionEventPublisher(
                kafka, objectMapper, "rollout-topic", Duration.ofSeconds(1));
    }

    @Test
    void publishesOnlyTheBoundedInvalidationContract() throws Exception {
        when(kafka.send(org.mockito.ArgumentMatchers
                .<ProducerRecord<String, String>>any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Instant occurredAt = Instant.parse("2026-08-21T09:00:00Z");

        publisher.publish(List.of(
                new FeatureRolloutDecisionOutboxRepository.DecisionEvent(
                        eventId, null, "ALL", "ux.product-surfaces.services.v1",
                        12, "PAUSED", 1, occurredAt)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("rollout-topic");
        assertThat(record.key()).isEqualTo("ux.product-surfaces.services.v1");
        assertThat(new String(
                record.headers().lastHeader("dwp-event-id").value(),
                StandardCharsets.UTF_8)).isEqualTo(eventId.toString());
        assertThat(new String(
                record.headers().lastHeader("dwp-event-type").value(),
                StandardCharsets.UTF_8))
                .isEqualTo(KafkaFeatureRolloutDecisionEventPublisher.EVENT_TYPE);
        JsonNode payload = objectMapper.readTree(record.value());
        assertThat(payload.propertyStream().map(entry -> entry.getKey()).collect(
                java.util.stream.Collectors.toSet())).containsExactlyInAnyOrderElementsOf(Set.of(
                        "eventId", "tenantScope", "tenantId", "flagKey",
                        "opaqueRevision", "state", "occurredAt"));
        assertThat(payload.path("opaqueRevision").asText())
                .isEqualTo("rev-00000000000000000012");
        assertThat(payload.path("tenantId").isNull()).isTrue();
        assertThat(payload.path("occurredAt").isTextual()).isTrue();
        assertThat(payload.path("occurredAt").asText()).isEqualTo(occurredAt.toString());
    }

    @Test
    void publishesExactScopeWithTheAuthTenantLongExpectedByGateway() throws Exception {
        when(kafka.send(org.mockito.ArgumentMatchers
                .<ProducerRecord<String, String>>any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        publisher.publish(List.of(
                new FeatureRolloutDecisionOutboxRepository.DecisionEvent(
                        UUID.fromString("22222222-2222-2222-2222-222222222222"),
                        42L,
                        "EXACT",
                        "ux.product-surfaces.approvals.v1",
                        13,
                        "ROLLED_BACK",
                        1,
                        Instant.parse("2026-08-24T00:00:00Z"))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        JsonNode payload = objectMapper.readTree(captor.getValue().value());

        assertThat(payload.path("tenantScope").asText()).isEqualTo("EXACT");
        assertThat(payload.path("tenantId").isIntegralNumber()).isTrue();
        assertThat(payload.path("tenantId").longValue()).isEqualTo(42L);
        assertThat(payload.path("opaqueRevision").asText())
                .isEqualTo("rev-00000000000000000013");
    }

    @Test
    void leavesOutboxUnpublishedWhenTransportFails() {
        when(kafka.send(org.mockito.ArgumentMatchers
                .<ProducerRecord<String, String>>any())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        var event = new FeatureRolloutDecisionOutboxRepository.DecisionEvent(
                UUID.randomUUID(), null, "ALL", "ux.product-surfaces.hcm.v1",
                1, "ROLLED_BACK", 1, Instant.parse("2026-08-21T09:00:00Z"));

        assertThatThrownBy(() -> publisher.publish(List.of(event)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publication failed");
    }
}
