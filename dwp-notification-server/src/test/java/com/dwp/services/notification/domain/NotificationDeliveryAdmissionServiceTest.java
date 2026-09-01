package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationDeliveryAdmissionRepository.AdmissionClaim;
import com.dwp.services.notification.domain.NotificationDeliveryAdmissionRepository.SuppressionMatch;
import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDeliveryAdmissionServiceTest {

    private NotificationDeliveryAdmissionRepository repository;
    private NotificationDeliveryAdmissionService service;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationDeliveryAdmissionRepository.class);
        service = new NotificationDeliveryAdmissionService(repository, Duration.ofHours(1));
    }

    @Test
    void suppressesARegularNotificationInsideAnActiveSuppression() {
        UUID receiptId = UUID.randomUUID();
        UUID suppressionId = UUID.randomUUID();
        when(repository.claim(any(Long.class), any(), any(), any(Long.class), eq("IN_APP")))
                .thenReturn(new AdmissionClaim(receiptId, "PENDING", true));
        when(repository.matchingSuppression(
                eq(7L), eq("messaging"), eq("MESSAGING.CHANNEL_MESSAGE"),
                eq("IN_APP"), any()))
                .thenReturn(new SuppressionMatch(
                        suppressionId, true, "APP", "messaging"));

        List<Long> result = service.admittedRecipients(
                7, request(), contract("NORMAL", "ACTIONABLE"), Instant.now());

        assertThat(result).isEmpty();
        verify(repository).complete(
                7, receiptId, "SUPPRESSED", "ACTIVE_SUPPRESSION",
                suppressionId, null);
        verify(repository, never()).maximumPerWindow(any(Long.class), any(), any(), any());
    }

    @Test
    void allowsAnUrgentNotificationWhenTheSuppressionPermitsCriticalBypass() {
        UUID receiptId = UUID.randomUUID();
        UUID suppressionId = UUID.randomUUID();
        when(repository.claim(any(Long.class), any(), any(), any(Long.class), eq("IN_APP")))
                .thenReturn(new AdmissionClaim(receiptId, "PENDING", true));
        when(repository.matchingSuppression(any(Long.class), any(), any(), any(), any()))
                .thenReturn(new SuppressionMatch(
                        suppressionId, true, "TENANT", "*"));
        when(repository.maximumPerWindow(any(Long.class), any(), any(), any()))
                .thenReturn(null);

        List<Long> result = service.admittedRecipients(
                7, request(), contract("URGENT", "CRITICAL"), Instant.now());

        assertThat(result).containsExactly(91L);
        verify(repository).complete(
                7, receiptId, "ADMITTED", "CRITICAL_BYPASS",
                suppressionId, null);
    }

    @Test
    void atomicallyRejectsARecipientWhenTheEffectiveWindowIsFull() {
        UUID receiptId = UUID.randomUUID();
        when(repository.claim(any(Long.class), any(), any(), any(Long.class), eq("IN_APP")))
                .thenReturn(new AdmissionClaim(receiptId, "PENDING", true));
        when(repository.maximumPerWindow(any(Long.class), any(), any(), any()))
                .thenReturn(3);
        when(repository.incrementWindow(
                any(Long.class), any(Long.class), any(), any(), any(), eq(3600), eq(3)))
                .thenReturn(false);

        List<Long> result = service.admittedRecipients(
                7, request(), contract("NORMAL", "ACTIONABLE"), Instant.now());

        assertThat(result).isEmpty();
        verify(repository).complete(
                eq(7L), eq(receiptId), eq("RATE_LIMITED"), eq("MAX_PER_WINDOW"),
                eq(null), any(Instant.class));
    }

    @Test
    void reusesAnExistingAdmissionDecisionWithoutChargingTheWindowAgain() {
        when(repository.claim(any(Long.class), any(), any(), any(Long.class), eq("IN_APP")))
                .thenReturn(new AdmissionClaim(UUID.randomUUID(), "ADMITTED", false));

        List<Long> result = service.admittedRecipients(
                7, request(), contract("NORMAL", "ACTIONABLE"), Instant.now());

        assertThat(result).containsExactly(91L);
        verify(repository, never()).incrementWindow(
                any(Long.class), any(Long.class), any(), any(), any(), any(Integer.class),
                any(Integer.class));
    }

    @Test
    void alignsFixedWindowsToEpochBoundaries() {
        Instant result = NotificationDeliveryAdmissionService.windowStart(
                Instant.parse("2026-08-20T10:59:59Z"), Duration.ofHours(1));

        assertThat(result).isEqualTo(Instant.parse("2026-08-20T10:00:00Z"));
    }

    private DirectMaterializationRequest request() {
        return new DirectMaterializationRequest(
                UUID.randomUUID(),
                "messaging.message.sent.v1",
                1,
                "MESSAGING.CHANNEL_MESSAGE",
                List.of(91L),
                "channel:44",
                "ko-KR",
                "DIRECT_RECIPIENT",
                null,
                null,
                null,
                Instant.now(),
                null,
                false,
                Map.of("senderName", "Tester"));
    }

    private TemplateContract contract(String priority, String urgency) {
        return new TemplateContract(
                UUID.randomUUID(),
                0,
                UUID.randomUUID(),
                0,
                null,
                "MESSAGING.CHANNEL_MESSAGE",
                "messaging",
                priority,
                urgency,
                "ko-KR",
                "title",
                "preview",
                "body",
                Map.of());
    }
}
