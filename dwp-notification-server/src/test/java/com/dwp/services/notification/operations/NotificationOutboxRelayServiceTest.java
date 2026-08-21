package com.dwp.services.notification.operations;

import com.dwp.services.notification.operations.NotificationOutboxRepository.OutboxEvent;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationOutboxRelayServiceTest {

    @Test
    void publishesACloudEventsEnvelopeAndMarksTheLeaseComplete() throws Exception {
        NotificationDatabaseScope scope = mock(NotificationDatabaseScope.class);
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        OutboxEvent event = event(1);
        when(repository.lease(eq(7L), anyString(), any(), any(), eq(50)))
                .thenReturn(List.of(event));
        when(kafka.send(eq("dwp.notification.outbox.v1"), eq(event.eventKey()), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markPublished(eq(7L), eq(event.outboxId()), anyString(), any()))
                .thenReturn(true);
        NotificationOutboxRelayService service = service(scope, repository, kafka, mapper, 12);

        var result = service.relayTenant(7, Instant.parse("2026-08-20T09:00:00Z"));

        assertThat(result.published()).isEqualTo(1);
        var envelope = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafka).send(
                eq("dwp.notification.outbox.v1"), eq(event.eventKey()), envelope.capture());
        JsonNode root = mapper.readTree(envelope.getValue());
        assertThat(root.path("specversion").asText()).isEqualTo("1.0");
        assertThat(root.path("id").asText()).isEqualTo(event.outboxId().toString());
        assertThat(root.path("tenantid").asText()).isEqualTo("7");
        assertThat(root.path("data").path("notificationId").asText()).isEqualTo("n-1");
        verify(repository).markPublished(eq(7L), eq(event.outboxId()), anyString(), any());
    }

    @Test
    void retainsADeadEventAfterTheRetryBudgetIsExhausted() {
        NotificationDatabaseScope scope = mock(NotificationDatabaseScope.class);
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OutboxEvent event = event(3);
        when(repository.lease(eq(7L), anyString(), any(), any(), eq(50)))
                .thenReturn(List.of(event));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));
        when(repository.markFailed(
                eq(7L), eq(event.outboxId()), anyString(), eq(3), eq(3),
                any(), anyString()))
                .thenReturn(true);
        NotificationOutboxRelayService service = service(
                scope, repository, kafka,
                new ObjectMapper().findAndRegisterModules(), 3);

        var result = service.relayTenant(7, Instant.parse("2026-08-20T09:00:00Z"));

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.dead()).isEqualTo(1);
        verify(repository).markFailed(
                eq(7L), eq(event.outboxId()), anyString(), eq(3), eq(3),
                any(), anyString());
    }

    private NotificationOutboxRelayService service(
            NotificationDatabaseScope scope,
            NotificationOutboxRepository repository,
            KafkaTemplate<String, String> kafka,
            ObjectMapper mapper,
            int maximumAttempts) {
        return new NotificationOutboxRelayService(
                scope,
                repository,
                kafka,
                mapper,
                "dwp.notification.outbox.v1",
                "test",
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofDays(7),
                maximumAttempts,
                50);
    }

    private OutboxEvent event(int attemptCount) {
        return new OutboxEvent(
                UUID.randomUUID(),
                7,
                "NOTIFICATION",
                "n-1",
                "notification.materialized",
                "materialized:e-1",
                "{\"notificationId\":\"n-1\"}",
                Instant.parse("2026-08-20T08:59:00Z"),
                attemptCount);
    }
}
