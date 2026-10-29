package com.dwp.services.platform.mail;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailWebhookIngressServiceTest {

    @Test
    void verifiedCallbackOnlyEnqueuesHashedReceipt() {
        MailWebhookVerificationPort verifier = mock(MailWebhookVerificationPort.class);
        MailSyncJobRepository jobs = mock(MailSyncJobRepository.class);
        UUID connectionId = UUID.randomUUID();
        when(verifier.supports("GOOGLE_GMAIL")).thenReturn(true);
        MailWebhookVerificationPort.Verification receipt =
                new MailWebhookVerificationPort.Verification(
                        true, "GOOGLE_GMAIL", 7, connectionId,
                        "a".repeat(64), "b".repeat(64),
                        OffsetDateTime.now(ZoneOffset.UTC), "corr", null);
        when(verifier.verify(any())).thenReturn(receipt);
        MailSyncJobRepository.WebhookEnqueue queued =
                new MailSyncJobRepository.WebhookEnqueue(UUID.randomUUID(), UUID.randomUUID());
        when(jobs.enqueueVerifiedWebhook(receipt, 5)).thenReturn(queued);
        MailWebhookIngressService service = new MailWebhookIngressService(
                List.of(verifier), jobs,
                new MailMetrics(new SimpleMeterRegistry()), 1024, 5);

        MailWebhookIngressUseCase.Accepted result = service.accept(
                "google_gmail", Map.of("X-Provider-Token", List.of("raw-secret")),
                "callback-body".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(result).isEqualTo(new MailWebhookIngressUseCase.Accepted(
                queued.operationId(), queued.jobId()));
        verify(jobs).enqueueVerifiedWebhook(receipt, 5);
        MailWebhookVerificationPort.Callback callback =
                new MailWebhookVerificationPort.Callback(
                        "GOOGLE_GMAIL", Map.of("token", List.of("raw-secret")),
                        "raw-body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(callback.toString()).doesNotContain("raw-secret", "raw-body");
    }

    @Test
    void missingProviderVerifierFailsClosed() {
        MailWebhookIngressService service = new MailWebhookIngressService(
                List.of(), mock(MailSyncJobRepository.class),
                new MailMetrics(new SimpleMeterRegistry()), 1024, 5);

        assertThatThrownBy(() -> service.accept(
                "NAVER_WORKS", Map.of(), new byte[]{1}))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("WEBHOOK_VERIFIER_UNAVAILABLE");
    }

    @Test
    void duplicateProviderVerifiersFailClosedInsteadOfDependingOnSpringOrder() {
        MailWebhookVerificationPort first = mock(MailWebhookVerificationPort.class);
        MailWebhookVerificationPort second = mock(MailWebhookVerificationPort.class);
        when(first.supports("GOOGLE_GMAIL")).thenReturn(true);
        when(second.supports("GOOGLE_GMAIL")).thenReturn(true);
        MailWebhookIngressService service = new MailWebhookIngressService(
                List.of(first, second), mock(MailSyncJobRepository.class),
                new MailMetrics(new SimpleMeterRegistry()), 1024, 5);

        assertThatThrownBy(() -> service.accept(
                "GOOGLE_GMAIL", Map.of(), new byte[]{1}))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("WEBHOOK_VERIFIER_AMBIGUOUS");
    }
}
