package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import com.dwp.services.platform.media.TenantMediaStorage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.ProviderType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailDeliveryWorkerTest {

    @Test
    void readyConnectorCompletesAClaimedDelivery() {
        MailDeliveryRepository repository = mock(MailDeliveryRepository.class);
        MailDeliveryCompletionService completion = mock(MailDeliveryCompletionService.class);
        MailConnectorPort connector = readyConnector();
        MailConnectorRegistry registry = new MailConnectorRegistry(List.of(connector));
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        MailDeliveryRepository.DeliveryJob job = job(ProviderType.DWP_SANDBOX);
        when(repository.claim(anyString(), eq(10), eq(30))).thenReturn(List.of(job));
        when(repository.authorizedSenderMode(job))
                .thenReturn(Optional.of(MailConnectorPort.SenderMode.ACCOUNT));
        MailDeliveryWorker worker = new MailDeliveryWorker(
                repository, registry, completion, storage, true, 10, 30, 5, "test");

        worker.deliverPending();

        verify(repository).releaseExpiredLeases();
        verify(completion).complete(
                eq(job), anyString(), any(), eq(MailConnectorPort.SenderMode.ACCOUNT));
        ArgumentCaptor<MailConnectorPort.SendRequest> request =
                ArgumentCaptor.forClass(MailConnectorPort.SendRequest.class);
        verify(connector).send(request.capture());
        assertThat(request.getValue().idempotencyKey()).isEqualTo(job.deliveryId());
        assertThat(request.getValue().toRecipients()).containsExactly("to@sk.com");
        assertThat(request.getValue().ccRecipients()).containsExactly("cc@sk.com");
        assertThat(request.getValue().bccRecipients()).containsExactly("bcc@sk.com");
    }

    @Test
    void missingRuntimeAdapterFailsWithoutRepeatedRetries() {
        MailDeliveryRepository repository = mock(MailDeliveryRepository.class);
        MailDeliveryCompletionService completion = mock(MailDeliveryCompletionService.class);
        MailConnectorRegistry registry = new MailConnectorRegistry(
                List.of(new DwpSandboxMailConnector()));
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        MailDeliveryRepository.DeliveryJob job = job(ProviderType.MICROSOFT_GRAPH);
        when(repository.claim(anyString(), eq(10), eq(30))).thenReturn(List.of(job));
        MailDeliveryWorker worker = new MailDeliveryWorker(
                repository, registry, completion, storage, true, 10, 30, 5, "test");

        worker.deliverPending();

        verify(completion).fail(
                eq(job), anyString(), eq(5), eq("MAIL_ADAPTER_NOT_DEPLOYED"), eq(true));
    }

    @Test
    void connectorWithoutSendCapabilityFailsBeforeProviderSubmission() {
        MailDeliveryRepository repository = mock(MailDeliveryRepository.class);
        MailDeliveryCompletionService completion = mock(MailDeliveryCompletionService.class);
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        MailConnectorPort connector = readyConnector(Set.of(
                MailConnectorPort.Capability.READ,
                MailConnectorPort.Capability.BCC));
        MailDeliveryRepository.DeliveryJob job = job(ProviderType.DWP_SANDBOX);
        when(repository.claim(anyString(), eq(10), eq(30))).thenReturn(List.of(job));
        when(repository.authorizedSenderMode(job))
                .thenReturn(Optional.of(MailConnectorPort.SenderMode.ACCOUNT));
        MailDeliveryWorker worker = new MailDeliveryWorker(
                repository, new MailConnectorRegistry(List.of(connector)), completion, storage,
                true, 10, 30, 5, "test");

        worker.deliverPending();

        verify(connector, never()).send(any());
        verify(completion).fail(eq(job), anyString(), eq(5),
                eq("MAIL_ADAPTER_SEND_NOT_SUPPORTED"), eq(true));
    }

    @Test
    void revokedSharedSenderIsRejectedImmediatelyBeforeProviderSubmission() {
        MailDeliveryRepository repository = mock(MailDeliveryRepository.class);
        MailDeliveryCompletionService completion = mock(MailDeliveryCompletionService.class);
        MailConnectorPort connector = readyConnector();
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        MailDeliveryRepository.DeliveryJob job = job(ProviderType.DWP_SANDBOX);
        when(repository.claim(anyString(), eq(10), eq(30))).thenReturn(List.of(job));
        when(repository.authorizedSenderMode(job)).thenReturn(Optional.empty());
        MailDeliveryWorker worker = new MailDeliveryWorker(
                repository, new MailConnectorRegistry(List.of(connector)), completion, storage,
                true, 10, 30, 5, "test");

        worker.deliverPending();

        verify(connector, never()).send(any());
        verify(completion).fail(
                eq(job), anyString(), eq(5), eq("MAIL_SEND_AUTHORIZATION_REVOKED"), eq(true));
    }

    @Test
    void ambiguousProviderFailureIsNotAutomaticallyRetried() {
        MailDeliveryRepository repository = mock(MailDeliveryRepository.class);
        MailDeliveryCompletionService completion = mock(MailDeliveryCompletionService.class);
        MailConnectorPort connector = readyConnector();
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        MailDeliveryRepository.DeliveryJob job = job(ProviderType.DWP_SANDBOX);
        when(repository.claim(anyString(), eq(10), eq(30))).thenReturn(List.of(job));
        when(repository.authorizedSenderMode(job))
                .thenReturn(Optional.of(MailConnectorPort.SenderMode.ACCOUNT));
        when(connector.send(any())).thenThrow(new RuntimeException("connection reset"));
        MailDeliveryWorker worker = new MailDeliveryWorker(
                repository, new MailConnectorRegistry(List.of(connector)), completion, storage,
                true, 10, 30, 5, "test");

        worker.deliverPending();

        verify(completion).fail(
                eq(job), anyString(), eq(5), eq("MAIL_PROVIDER_RESULT_UNKNOWN"), eq(true));
    }

    @Test
    void htmlAndMultipleAttachmentsReachTheConnectorWithTheStableDeliveryToken()
            throws Exception {
        MailDeliveryRepository repository = mock(MailDeliveryRepository.class);
        MailDeliveryCompletionService completion = mock(MailDeliveryCompletionService.class);
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        MailConnectorPort connector = readyConnector();
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);
        MailDeliveryRepository.DeliveryAttachment firstAttachment = attachment(
                "1/mail/first.txt", "first.txt", first);
        MailDeliveryRepository.DeliveryAttachment secondAttachment = attachment(
                "1/mail/second.txt", "second.txt", second);
        MailDeliveryRepository.DeliveryJob job = job(
                ProviderType.DWP_SANDBOX, "HTML",
                List.of(firstAttachment, secondAttachment));
        when(repository.claim(anyString(), eq(10), eq(30))).thenReturn(List.of(job));
        when(repository.authorizedSenderMode(job))
                .thenReturn(Optional.of(MailConnectorPort.SenderMode.ACCOUNT));
        when(storage.load(1L, firstAttachment.storageReference()))
                .thenReturn(new ByteArrayResource(first));
        when(storage.load(1L, secondAttachment.storageReference()))
                .thenReturn(new ByteArrayResource(second));
        MailDeliveryWorker worker = new MailDeliveryWorker(
                repository, new MailConnectorRegistry(List.of(connector)), completion, storage,
                true, 10, 30, 5, "test");

        worker.deliverPending();

        ArgumentCaptor<MailConnectorPort.SendRequest> request =
                ArgumentCaptor.forClass(MailConnectorPort.SendRequest.class);
        verify(connector).send(request.capture());
        assertThat(request.getValue().idempotencyKey()).isEqualTo(job.deliveryId());
        assertThat(request.getValue().bodyFormat()).isEqualTo(MailConnectorPort.BodyFormat.HTML);
        assertThat(request.getValue().attachments())
                .extracting(MailConnectorPort.OutboundAttachment::fileName)
                .containsExactly("first.txt", "second.txt");
        assertThat(request.getValue().attachments().get(0).content()).containsExactly(first);
        assertThat(request.getValue().attachments().get(1).content()).containsExactly(second);
        verify(completion).complete(
                eq(job), anyString(), any(), eq(MailConnectorPort.SenderMode.ACCOUNT));
    }

    @Test
    void unsupportedAttachmentCapabilityFailsBeforeProviderSubmission() {
        MailDeliveryRepository repository = mock(MailDeliveryRepository.class);
        MailDeliveryCompletionService completion = mock(MailDeliveryCompletionService.class);
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        MailConnectorPort connector = readyConnector(Set.of(
                MailConnectorPort.Capability.SEND,
                MailConnectorPort.Capability.BCC));
        byte[] content = "attachment".getBytes(StandardCharsets.UTF_8);
        MailDeliveryRepository.DeliveryJob job = job(
                ProviderType.DWP_SANDBOX, "TEXT",
                List.of(attachment("1/mail/file.txt", "file.txt", content)));
        when(repository.claim(anyString(), eq(10), eq(30))).thenReturn(List.of(job));
        when(repository.authorizedSenderMode(job))
                .thenReturn(Optional.of(MailConnectorPort.SenderMode.ACCOUNT));
        MailDeliveryWorker worker = new MailDeliveryWorker(
                repository, new MailConnectorRegistry(List.of(connector)), completion, storage,
                true, 10, 30, 5, "test");

        worker.deliverPending();

        verify(connector, never()).send(any());
        verify(completion).fail(eq(job), anyString(), eq(5),
                eq("MAIL_ADAPTER_ATTACHMENTS_NOT_SUPPORTED"), eq(true));
    }

    @Test
    void attachmentLoadFailureIsRetryableAndNeverCallsTheProvider() {
        MailDeliveryRepository repository = mock(MailDeliveryRepository.class);
        MailDeliveryCompletionService completion = mock(MailDeliveryCompletionService.class);
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        MailConnectorPort connector = readyConnector();
        byte[] content = "attachment".getBytes(StandardCharsets.UTF_8);
        MailDeliveryRepository.DeliveryAttachment attachment = attachment(
                "1/mail/missing.txt", "missing.txt", content);
        MailDeliveryRepository.DeliveryJob job = job(
                ProviderType.DWP_SANDBOX, "TEXT", List.of(attachment));
        when(repository.claim(anyString(), eq(10), eq(30))).thenReturn(List.of(job));
        when(repository.authorizedSenderMode(job))
                .thenReturn(Optional.of(MailConnectorPort.SenderMode.ACCOUNT));
        when(storage.load(1L, attachment.storageReference()))
                .thenThrow(new IllegalStateException("temporarily unavailable"));
        MailDeliveryWorker worker = new MailDeliveryWorker(
                repository, new MailConnectorRegistry(List.of(connector)), completion, storage,
                true, 10, 30, 5, "test");

        worker.deliverPending();

        verify(connector, never()).send(any());
        verify(completion).fail(eq(job), anyString(), eq(5),
                eq("MAIL_ATTACHMENT_LOAD_FAILED"), eq(false));
    }

    @Test
    void sendOnBehalfModeReachesSupportingConnector() {
        MailDeliveryRepository repository = mock(MailDeliveryRepository.class);
        MailDeliveryCompletionService completion = mock(MailDeliveryCompletionService.class);
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        MailConnectorPort connector = readyConnector();
        MailDeliveryRepository.DeliveryJob job = job(ProviderType.DWP_SANDBOX);
        when(repository.claim(anyString(), eq(10), eq(30))).thenReturn(List.of(job));
        when(repository.authorizedSenderMode(job))
                .thenReturn(Optional.of(MailConnectorPort.SenderMode.SEND_ON_BEHALF));
        MailDeliveryWorker worker = new MailDeliveryWorker(
                repository, new MailConnectorRegistry(List.of(connector)), completion, storage,
                true, 10, 30, 5, "test");

        worker.deliverPending();

        ArgumentCaptor<MailConnectorPort.SendRequest> request =
                ArgumentCaptor.forClass(MailConnectorPort.SendRequest.class);
        verify(connector).send(request.capture());
        assertThat(request.getValue().senderMode())
                .isEqualTo(MailConnectorPort.SenderMode.SEND_ON_BEHALF);
        verify(completion).complete(
                eq(job), anyString(), any(),
                eq(MailConnectorPort.SenderMode.SEND_ON_BEHALF));
    }

    @Test
    void unsupportedSendOnBehalfFailsBeforeProviderSubmission() {
        MailDeliveryRepository repository = mock(MailDeliveryRepository.class);
        MailDeliveryCompletionService completion = mock(MailDeliveryCompletionService.class);
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        MailConnectorPort connector = readyConnector(Set.of(
                MailConnectorPort.Capability.SEND,
                MailConnectorPort.Capability.BCC));
        MailDeliveryRepository.DeliveryJob job = job(ProviderType.DWP_SANDBOX);
        when(repository.claim(anyString(), eq(10), eq(30))).thenReturn(List.of(job));
        when(repository.authorizedSenderMode(job))
                .thenReturn(Optional.of(MailConnectorPort.SenderMode.SEND_ON_BEHALF));
        MailDeliveryWorker worker = new MailDeliveryWorker(
                repository, new MailConnectorRegistry(List.of(connector)), completion, storage,
                true, 10, 30, 5, "test");

        worker.deliverPending();

        verify(connector, never()).send(any());
        verify(completion).fail(eq(job), anyString(), eq(5),
                eq("MAIL_ADAPTER_SEND_ON_BEHALF_NOT_SUPPORTED"), eq(true));
    }

    private MailConnectorPort readyConnector() {
        return readyConnector(Set.of(
                MailConnectorPort.Capability.SEND,
                MailConnectorPort.Capability.SEND_ON_BEHALF,
                MailConnectorPort.Capability.BCC,
                MailConnectorPort.Capability.HTML_BODY,
                MailConnectorPort.Capability.ATTACHMENTS));
    }

    private MailConnectorPort readyConnector(Set<MailConnectorPort.Capability> capabilities) {
        MailConnectorPort connector = mock(MailConnectorPort.class);
        when(connector.manifest()).thenReturn(new MailConnectorPort.Manifest(
                MailConnectorPort.ProviderFamily.DWP_SANDBOX,
                "test", "test", capabilities));
        when(connector.readiness(any())).thenReturn(new MailConnectorPort.Readiness(
                MailConnectorPort.ReadinessState.READY, Instant.now(), null, null));
        when(connector.send(any())).thenReturn(new MailConnectorPort.DeliveryReceipt(
                "provider-message", "provider-thread", Instant.now()));
        return connector;
    }

    private MailDeliveryRepository.DeliveryJob job(ProviderType providerType) {
        return new MailDeliveryRepository.DeliveryJob(
                UUID.randomUUID(), 1L, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "corr-delivery", 7L,
                UUID.randomUUID(), providerType, null, "sk.com", UUID.randomUUID(),
                "sandbox:user:7", "sender@sk.com", "Sender", "Subject", "Body",
                List.of("to@sk.com"), List.of("cc@sk.com"), List.of("bcc@sk.com"), null);
    }

    private MailDeliveryRepository.DeliveryJob job(
            ProviderType providerType,
            String bodyFormat,
            List<MailDeliveryRepository.DeliveryAttachment> attachments) {
        return new MailDeliveryRepository.DeliveryJob(
                UUID.randomUUID(), 1L, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "corr-delivery", 7L,
                UUID.randomUUID(), providerType, null, "sk.com", UUID.randomUUID(),
                "sandbox:user:7", "sender@sk.com", "Sender", "Subject", bodyFormat,
                "<p>Body</p>", List.of("to@sk.com"), List.of("cc@sk.com"),
                List.of("bcc@sk.com"), attachments.size(), attachments, null);
    }

    private MailDeliveryRepository.DeliveryAttachment attachment(
            String storageReference, String fileName, byte[] content) {
        return new MailDeliveryRepository.DeliveryAttachment(
                UUID.randomUUID(), storageReference, fileName, "text/plain",
                content.length, sha256(content));
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
