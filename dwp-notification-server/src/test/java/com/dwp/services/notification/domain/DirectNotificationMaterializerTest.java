package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationMaterializationRepository.PersistenceResult;
import com.dwp.services.notification.domain.NotificationMaterializationRepository.TemplateContract;
import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.dwp.services.notification.domain.NotificationModels.MaterializationResult;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DirectNotificationMaterializerTest {

    private final NotificationMaterializationTransactions transactions =
            mock(NotificationMaterializationTransactions.class);
    private final NotificationProducerOwnershipPolicy ownership =
            mock(NotificationProducerOwnershipPolicy.class);
    private final NotificationRecipientEntitlementAdmission admission =
            mock(NotificationRecipientEntitlementAdmission.class);
    private final DirectNotificationMaterializer materializer =
            new DirectNotificationMaterializer(
                    transactions, ownership, admission,
                    new ObjectMapper().findAndRegisterModules());

    @Test
    void passesOnlyFreshlyEntitledRecipientsIntoTheWriteTransaction() {
        TemplateContract contract = contract();
        DirectMaterializationRequest request = request();
        UUID intentId = UUID.randomUUID();
        when(transactions.contract(
                7L, request.typeKey(), request.sourceEventType(), 1, "ko-KR"))
                .thenReturn(contract);
        when(admission.admittedRecipients(
                7L, List.of(11L, 12L), "messaging"))
                .thenReturn(Set.of(11L));
        when(transactions.materialize(
                eq(7L), any(), eq(contract), any(), anyString(), eq("correlation-1"),
                eq(Set.of(11L)), any(Instant.class)))
                .thenReturn(new PersistenceResult(
                        new MaterializationResult(intentId, UUID.randomUUID(), 1, false, "1"),
                        List.of()));

        MaterializationResult result = materializer.materialize(
                actor(), request, "correlation-1");

        assertThat(result.intentId()).isEqualTo(intentId);
        assertThat(result.recipientCount()).isOne();
        verify(admission).admittedRecipients(
                7L, List.of(11L, 12L), "messaging");
    }

    @Test
    void performsNoWriteWhenEntitlementValidationIsUnavailable() {
        DirectMaterializationRequest request = request();
        when(transactions.contract(
                anyLong(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(contract());
        when(admission.admittedRecipients(anyLong(), any(), anyString()))
                .thenThrow(new NotificationException(
                        NotificationErrorCode.NOTIFICATION_IDENTITY_UNAVAILABLE));

        assertThatThrownBy(() -> materializer.materialize(
                actor(), request, "correlation-1"))
                .isInstanceOf(NotificationException.class);
        verify(transactions, never()).materialize(
                anyLong(), any(), any(), any(), anyString(), anyString(), any(), any());
    }

    private NotificationRequestContext.Actor actor() {
        return new NotificationRequestContext.Actor(
                7L, null, Set.of(), Set.of(), true, "dwp-messaging-server");
    }

    private DirectMaterializationRequest request() {
        return new DirectMaterializationRequest(
                UUID.randomUUID(),
                "messaging.message.sent.v1",
                1,
                "MESSAGING.DIRECT_MESSAGE",
                List.of(11L, 12L),
                "conversation:1",
                "ko-KR",
                "DIRECT_MESSAGE",
                "user:10",
                "conversation:1",
                "/rooms/1",
                Instant.parse("2026-08-28T01:00:00Z"),
                null,
                false,
                Map.of("senderName", "Sender", "messagePreview", "Hello"));
    }

    private TemplateContract contract() {
        return new TemplateContract(
                UUID.randomUUID(), 0L, UUID.randomUUID(), 0L, null,
                "MESSAGING.DIRECT_MESSAGE", "messaging", "NORMAL", "INFORMATIONAL",
                "ko-KR", "{{senderName}}", "{{messagePreview}}", "{{messagePreview}}",
                Map.of());
    }
}
