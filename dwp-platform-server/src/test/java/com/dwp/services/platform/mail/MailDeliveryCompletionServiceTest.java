package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.ProviderType;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MailDeliveryCompletionServiceTest {

    @Mock
    private MailDeliveryRepository deliveries;
    @Mock
    private MailCommandRepository commands;
    @Mock
    private MailInboundMessageService inboundMessages;

    private MailDeliveryCompletionService service;

    @BeforeEach
    void setUp() {
        service = new MailDeliveryCompletionService(deliveries, commands, inboundMessages);
    }

    @Test
    void completedSandboxDeliveryInvokesInboundHookAndEmitsEvidence() {
        MailDeliveryRepository.DeliveryJob job = sandboxJob();
        String workerId = "sandbox-worker";
        Instant acceptedInstant = Instant.parse("2026-09-17T01:15:30Z");
        MailConnectorPort.DeliveryReceipt receipt = new MailConnectorPort.DeliveryReceipt(
                "sandbox:message:accepted", "sandbox:thread:accepted", acceptedInstant);
        UUID inboundThreadId = UUID.randomUUID();
        UUID inboundMessageId = UUID.randomUUID();
        OffsetDateTime receivedAt = OffsetDateTime.parse("2026-09-17T10:15:30+09:00");
        MailDeliveryRepository.InboundMessage inbound =
                new MailDeliveryRepository.InboundMessage(
                        inboundThreadId, inboundMessageId, receivedAt);
        when(deliveries.markDelivered(job, workerId, receipt)).thenReturn(1);
        when(deliveries.mirrorSandboxDelivery(
                job, receipt, MailConnectorPort.SenderMode.ACCOUNT))
                .thenReturn(List.of(inbound));

        service.complete(job, workerId, receipt);

        verify(deliveries).markDelivered(job, workerId, receipt);
        verify(inboundMessages).inboundMessageMaterialized(
                job.tenantId(), inboundThreadId, inboundMessageId, receivedAt);
        verify(deliveries).mirrorSandboxDelivery(
                job, receipt, MailConnectorPort.SenderMode.ACCOUNT);
        verify(commands).audit(
                job.tenantId(), job.createdBy(), "mail.message.sent", "MAIL_MESSAGE",
                job.messageId().toString(), job.correlationId(),
                Map.of("deliveryState", "SENDING", "attempt", job.attemptCount()),
                Map.of(
                        "deliveryState", "SENT",
                        "attempt", job.attemptCount(),
                        "providerType", ProviderType.DWP_SANDBOX.name(),
                        "acceptedAt", "2026-09-17T01:15:30Z"));
        verify(commands).domainEvent(
                job.tenantId(), "MAIL_MESSAGE", job.messageId(), "mail.message.sent",
                Map.of(
                        "messageId", job.messageId(),
                        "threadId", job.threadId(),
                        "providerType", ProviderType.DWP_SANDBOX.name(),
                        "acceptedAt", "2026-09-17T01:15:30Z"),
                job.correlationId());
        verifyNoMoreInteractions(inboundMessages);
    }

    @Test
    void staleCompletionDoesNotMirrorOrInvokeInboundHook() {
        MailDeliveryRepository.DeliveryJob job = sandboxJob();
        String workerId = "stale-worker";
        MailConnectorPort.DeliveryReceipt receipt = new MailConnectorPort.DeliveryReceipt(
                "sandbox:message:stale", "sandbox:thread:stale",
                Instant.parse("2026-09-17T01:15:30Z"));
        when(deliveries.markDelivered(job, workerId, receipt)).thenReturn(0);

        service.complete(job, workerId, receipt);

        verify(deliveries).markDelivered(job, workerId, receipt);
        verify(deliveries, never()).mirrorSandboxDelivery(
                job, receipt, MailConnectorPort.SenderMode.ACCOUNT);
        verifyNoInteractions(inboundMessages, commands);
        verifyNoMoreInteractions(deliveries);
    }

    private MailDeliveryRepository.DeliveryJob sandboxJob() {
        return new MailDeliveryRepository.DeliveryJob(
                UUID.randomUUID(), 42L, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 2, "corr-complete", 7L,
                UUID.randomUUID(), ProviderType.DWP_SANDBOX, null, "example.com",
                UUID.randomUUID(), "sandbox:user:7", "sender@example.com", "Sender",
                "Subject", "Body", List.of("recipient@example.com"), List.of(), List.of(), null);
    }
}
