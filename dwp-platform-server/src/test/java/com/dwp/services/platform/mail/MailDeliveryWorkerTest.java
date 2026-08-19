package com.dwp.services.platform.mail;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.ProviderType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailDeliveryWorkerTest {

    @Test
    void readyConnectorCompletesAClaimedDelivery() {
        MailDeliveryRepository repository = mock(MailDeliveryRepository.class);
        MailDeliveryCompletionService completion = mock(MailDeliveryCompletionService.class);
        MailConnectorRegistry registry = new MailConnectorRegistry(
                List.of(new DwpSandboxMailConnector()));
        MailDeliveryRepository.DeliveryJob job = job(ProviderType.DWP_SANDBOX);
        when(repository.claim(anyString(), eq(10), eq(30))).thenReturn(List.of(job));
        MailDeliveryWorker worker = new MailDeliveryWorker(
                repository, registry, completion, true, 10, 30, 5, "test");

        worker.deliverPending();

        verify(repository).releaseExpiredLeases();
        verify(completion).complete(eq(job), anyString(), any());
    }

    @Test
    void missingRuntimeAdapterFailsWithoutRepeatedRetries() {
        MailDeliveryRepository repository = mock(MailDeliveryRepository.class);
        MailDeliveryCompletionService completion = mock(MailDeliveryCompletionService.class);
        MailConnectorRegistry registry = new MailConnectorRegistry(
                List.of(new DwpSandboxMailConnector()));
        MailDeliveryRepository.DeliveryJob job = job(ProviderType.MICROSOFT_GRAPH);
        when(repository.claim(anyString(), eq(10), eq(30))).thenReturn(List.of(job));
        MailDeliveryWorker worker = new MailDeliveryWorker(
                repository, registry, completion, true, 10, 30, 5, "test");

        worker.deliverPending();

        verify(completion).fail(
                eq(job), anyString(), eq(5), eq("MAIL_ADAPTER_NOT_DEPLOYED"), eq(true));
    }

    private MailDeliveryRepository.DeliveryJob job(ProviderType providerType) {
        return new MailDeliveryRepository.DeliveryJob(
                UUID.randomUUID(), 1L, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "corr-delivery", 7L,
                UUID.randomUUID(), providerType, null, "sk.com", UUID.randomUUID(),
                "sandbox:user:7", "sender@sk.com", "Sender", "Subject", "Body",
                List.of("recipient@sk.com"), null);
    }
}
