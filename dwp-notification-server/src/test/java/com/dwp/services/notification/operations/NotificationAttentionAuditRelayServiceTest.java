package com.dwp.services.notification.operations;

import com.dwp.audit.AuditEvent;
import com.dwp.audit.AuditEventPublisher;
import com.dwp.services.notification.operations.NotificationAttentionAuditOutboxRepository.AttentionAuditEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationAttentionAuditRelayServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

    private final NotificationAttentionAuditRelayTransaction transactions =
            mock(NotificationAttentionAuditRelayTransaction.class);
    private final AuditEventPublisher publisher = mock(AuditEventPublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final NotificationAttentionAuditRelayService relay =
            new NotificationAttentionAuditRelayService(
                    transactions,
                    publisher,
                    meters,
                    "notification-1",
                    "test",
                    Duration.ofSeconds(30),
                    Duration.ofDays(30),
                    20,
                    100,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void marksPublishedOnlyAfterCanonicalAcceptanceWithContentFreeEvidence() {
        AttentionAuditEvent source = event("RULE_CREATED", 1);
        when(transactions.lease(
                eq(42L), anyString(), eq(NOW), eq(NOW.plusSeconds(30)), eq(100)))
                .thenReturn(List.of(source));
        when(publisher.publish(any())).thenReturn(AuditEventPublisher.DeliveryResult.ACCEPTED);
        when(transactions.markPublished(
                eq(42L), eq(source.eventId()), anyString(), eq(NOW))).thenReturn(true);

        var result = relay.relayTenant(42L, NOW);

        assertThat(result.published()).isOne();
        assertThat(result.retried()).isZero();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditEvent>> events = ArgumentCaptor.forClass(List.class);
        verify(publisher).publish(events.capture());
        assertThat(events.getValue()).singleElement().satisfies(published -> {
            assertThat(published.eventId()).isEqualTo(source.eventId());
            assertThat(published.tenantId()).isEqualTo(42L);
            assertThat(published.actorId()).isEqualTo("17");
            assertThat(published.targetId()).isEqualTo(source.subjectId().toString());
            assertThat(published.metadata())
                    .containsOnlyKeys("subjectVersion", "scopeKind", "effect")
                    .doesNotContainValue("private notification content");
        });
    }

    @Test
    void crashAfterPublishReplaysTheSameCanonicalEventId() {
        AttentionAuditEvent source = event("RULE_UPDATED", 2);
        when(transactions.lease(
                eq(42L), anyString(), any(), any(), eq(100)))
                .thenReturn(List.of(source));
        List<UUID> publishedIds = new ArrayList<>();
        when(publisher.publish(any())).thenAnswer(invocation -> {
            List<AuditEvent> events = invocation.getArgument(0);
            publishedIds.add(events.get(0).eventId());
            return AuditEventPublisher.DeliveryResult.ACCEPTED;
        });
        when(transactions.markPublished(
                eq(42L), eq(source.eventId()), anyString(), eq(NOW)))
                .thenReturn(false, true);

        var lostCompletion = relay.relayTenant(42L, NOW);
        var replayed = relay.relayTenant(42L, NOW.plusSeconds(31));

        assertThat(lostCompletion.leaseLost()).isOne();
        assertThat(replayed.published()).isOne();
        assertThat(publishedIds).containsExactly(source.eventId(), source.eventId());
    }

    @Test
    void isolatesARejectedPoisonFactWithoutBlockingValidSiblings() {
        AttentionAuditEvent first = event("RULE_CREATED", 1);
        AttentionAuditEvent poison = event("RULE_DELETED", 1);
        AttentionAuditEvent last = event("RULE_UPDATED", 1);
        when(transactions.lease(
                eq(42L), anyString(), any(), any(), eq(100)))
                .thenReturn(List.of(first, poison, last));
        when(publisher.publish(any())).thenAnswer(invocation -> {
            List<AuditEvent> events = invocation.getArgument(0);
            return events.stream().anyMatch(event -> event.action().endsWith("deleted"))
                    ? AuditEventPublisher.DeliveryResult.REJECTED
                    : AuditEventPublisher.DeliveryResult.ACCEPTED;
        });
        when(transactions.markPublished(anyLong(), any(), anyString(), any()))
                .thenReturn(true);
        when(transactions.markFailed(
                anyLong(), any(), anyString(), anyInt(), anyInt(), any(), anyString()))
                .thenReturn(true);

        var result = relay.relayTenant(42L, NOW);

        assertThat(result.published()).isEqualTo(2);
        assertThat(result.dead()).isOne();
        assertThat(result.retried()).isZero();
        verify(transactions).markFailed(
                eq(42L), eq(poison.eventId()), anyString(), eq(20), eq(20),
                any(), anyString());
    }

    @Test
    void schedulesBoundedBackoffForRetryableTransportFailure() {
        AttentionAuditEvent source = event("RULE_UPDATED", 2);
        when(transactions.lease(
                eq(42L), anyString(), any(), any(), eq(100)))
                .thenReturn(List.of(source));
        when(publisher.publish(any()))
                .thenReturn(AuditEventPublisher.DeliveryResult.RETRYABLE_FAILURE);
        when(transactions.markFailed(
                anyLong(), any(), anyString(), anyInt(), anyInt(), any(), anyString()))
                .thenReturn(true);

        var result = relay.relayTenant(42L, NOW);

        assertThat(result.retried()).isOne();
        ArgumentCaptor<Instant> retryAt = ArgumentCaptor.forClass(Instant.class);
        verify(transactions).markFailed(
                eq(42L), eq(source.eventId()), anyString(), eq(2), eq(20),
                retryAt.capture(), anyString());
        assertThat(retryAt.getValue())
                .isAfterOrEqualTo(NOW.plusSeconds(2))
                .isBefore(NOW.plusSeconds(3));
    }

    private AttentionAuditEvent event(String type, int attempt) {
        return new AttentionAuditEvent(
                UUID.randomUUID(),
                42L,
                17L,
                "ATTENTION_RULE",
                UUID.randomUUID(),
                type,
                "ACTOR",
                "PRIORITIZE",
                3L,
                NOW.minusSeconds(5),
                attempt);
    }
}
