package com.dwp.services.platform.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MailInboundMessageServiceTest {

    @Mock
    private MailDeliveryRepository deliveries;
    @Mock
    private MailNotificationEvents notificationEvents;

    private MailInboundMessageService service;

    @BeforeEach
    void setUp() {
        service = new MailInboundMessageService(deliveries, notificationEvents);
    }

    @Test
    void materializedInboundMessageDelegatesWaitingFollowUpTransition() {
        long tenantId = 42L;
        UUID threadId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        OffsetDateTime receivedAt = OffsetDateTime.parse("2026-09-17T10:15:30+09:00");
        when(deliveries.markFollowUpsReplied(
                tenantId, threadId, messageId, receivedAt)).thenReturn(1);

        assertThat(service.inboundMessageMaterialized(
                tenantId, threadId, messageId, receivedAt)).isOne();

        verify(deliveries).markFollowUpsReplied(
                tenantId, threadId, messageId, receivedAt);
        verify(notificationEvents).newMailReceived(tenantId, threadId, messageId);
    }

    @Test
    void invalidInboundIdentityFailsClosedBeforeRepositoryAccess() {
        UUID threadId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        OffsetDateTime receivedAt = OffsetDateTime.parse("2026-09-17T10:15:30+09:00");

        assertThatThrownBy(() -> service.inboundMessageMaterialized(
                null, threadId, messageId, receivedAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.inboundMessageMaterialized(
                42L, null, messageId, receivedAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.inboundMessageMaterialized(
                42L, threadId, null, receivedAt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.inboundMessageMaterialized(
                42L, threadId, messageId, null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(deliveries, notificationEvents);
    }
}
