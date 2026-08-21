package com.dwp.services.notification.domain;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationIdempotencyRepository.Request;
import com.dwp.services.notification.domain.NotificationSuppressionModels.SuppressionCommand;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationSuppressionServiceTest {

    private NotificationDatabaseScope databaseScope;
    private NotificationSuppressionRepository repository;
    private NotificationIdempotencyRepository idempotency;
    private AuditOutboxRecorder audit;
    private NotificationSuppressionService service;

    @BeforeEach
    void setUp() {
        databaseScope = mock(NotificationDatabaseScope.class);
        repository = mock(NotificationSuppressionRepository.class);
        idempotency = mock(NotificationIdempotencyRepository.class);
        audit = mock(AuditOutboxRecorder.class);
        service = new NotificationSuppressionService(
                databaseScope, repository, idempotency, audit);
    }

    @Test
    void previewsANormalizedExternalChannelAsFailClosed() {
        Instant startsAt = Instant.now().plus(Duration.ofHours(1));
        when(repository.scopeExists(7, "APP", "messaging")).thenReturn(true);
        when(repository.affectedTypeCount(7, "APP", "messaging")).thenReturn(3L);
        when(repository.matchedTypeKeys(7, "APP", "messaging"))
                .thenReturn(java.util.List.of("MESSAGING.CHANNEL_MESSAGE"));

        var preview = service.preview(actor(), new SuppressionCommand(
                "app", "messaging", "email", startsAt,
                startsAt.plus(Duration.ofHours(2)), true, "Provider outage"));

        assertThat(preview.scopeType()).isEqualTo("APP");
        assertThat(preview.channel()).isEqualTo("EMAIL");
        assertThat(preview.affectedTypeCount()).isEqualTo(3);
        assertThat(preview.observedNotifications7Days()).isZero();
        assertThat(preview.riskFlags()).containsExactly("EXTERNAL_CHANNEL_DISABLED");
        verify(repository, never()).observedNotifications7Days(anyLong(), any(), any());
        verify(databaseScope).applyWorker(7);
    }

    @Test
    void rejectsSuppressionBeyondTheMaximumTtl() {
        Instant startsAt = Instant.now().plus(Duration.ofMinutes(10));
        when(repository.scopeExists(7, "TENANT", "*")).thenReturn(true);

        assertThatThrownBy(() -> service.preview(actor(), new SuppressionCommand(
                "TENANT", "ignored", "IN_APP", startsAt,
                startsAt.plus(Duration.ofDays(32)), true, "Too long")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("31 days");
    }

    @Test
    void rejectsAnOverlappingSuppressionBeforeMutation() {
        Instant startsAt = Instant.now().plus(Duration.ofMinutes(10));
        SuppressionCommand command = new SuppressionCommand(
                "TYPE", "MESSAGING.CHANNEL_MESSAGE", "IN_APP", startsAt,
                startsAt.plus(Duration.ofHours(1)), false, "Incident response");
        when(repository.scopeExists(7, "TYPE", "MESSAGING.CHANNEL_MESSAGE"))
                .thenReturn(true);
        when(idempotency.begin(eq(actor()), eq("incident-1"),
                eq("NOTIFICATION_SUPPRESSION_CREATE"), any()))
                .thenReturn(new Request("incident-1", "operation", "hash", null));
        when(repository.overlappingCount(eq(7L), any(), eq(startsAt))).thenReturn(1L);

        assertThatThrownBy(() -> service.create(actor(), command, "incident-1"))
                .isInstanceOf(NotificationException.class);

        verify(repository, never()).create(anyLong(), anyLong(), any(), any());
        verify(audit, never()).record(any(AuditEvent.class));
    }

    private NotificationRequestContext.Actor actor() {
        return new NotificationRequestContext.Actor(
                7, 91L, Set.of("TENANT_ADMIN"),
                Set.of("ADMIN.NOTIFICATION_OPERATIONS:MANAGE"),
                false, "dwp-gateway");
    }
}
