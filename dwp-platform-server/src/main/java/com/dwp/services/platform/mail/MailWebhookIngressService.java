package com.dwp.services.platform.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
class MailWebhookIngressService implements MailWebhookIngressUseCase {

    private static final Set<String> PROVIDERS = Set.of(
            "MICROSOFT_GRAPH", "GOOGLE_GMAIL", "NAVER_WORKS", "JMAP", "IMAP_SMTP");

    private final List<MailWebhookVerificationPort> verifiers;
    private final MailSyncJobRepository syncJobs;
    private final MailMetrics metrics;
    private final int maximumBodyBytes;
    private final int maximumAttempts;

    MailWebhookIngressService(
            List<MailWebhookVerificationPort> verifiers,
            MailSyncJobRepository syncJobs,
            MailMetrics metrics,
            @Value("${dwp.platform.mail.webhooks.maximum-body-bytes:1048576}")
            int maximumBodyBytes,
            @Value("${dwp.platform.mail.sync-worker.maximum-attempts:5}")
            int maximumAttempts) {
        this.verifiers = verifiers == null ? List.of() : List.copyOf(verifiers);
        this.syncJobs = syncJobs;
        this.metrics = metrics;
        this.maximumBodyBytes = positive(maximumBodyBytes, "maximumBodyBytes");
        this.maximumAttempts = positive(maximumAttempts, "maximumAttempts");
    }

    MailWebhookIngressUseCase.Accepted accept(
            String rawProvider,
            Map<String, List<String>> headers,
            byte[] body) {
        return acceptVerified(verify(rawProvider, headers, body));
    }

    @Override
    public MailWebhookVerificationPort.Verification verify(
            String rawProvider,
            Map<String, List<String>> headers,
            byte[] body) {
        String provider = rawProvider == null
                ? "" : rawProvider.trim().toUpperCase(Locale.ROOT);
        if (!PROVIDERS.contains(provider)) {
            metrics.webhook("rejected");
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "WEBHOOK_PROVIDER_UNSUPPORTED");
        }
        if (body == null || body.length == 0 || body.length > maximumBodyBytes) {
            metrics.webhook("rejected");
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "WEBHOOK_PAYLOAD_LIMIT_EXCEEDED");
        }
        List<MailWebhookVerificationPort> owners = verifiers.stream()
                .filter(candidate -> candidate.supports(provider))
                .toList();
        if (owners.isEmpty()) {
            metrics.webhook("unavailable");
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "WEBHOOK_VERIFIER_UNAVAILABLE");
        }
        if (owners.size() != 1) {
            metrics.webhook("ambiguous");
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "WEBHOOK_VERIFIER_AMBIGUOUS");
        }
        MailWebhookVerificationPort verifier = owners.getFirst();
        MailWebhookVerificationPort.Verification receipt;
        try {
            receipt = verifier.verify(new MailWebhookVerificationPort.Callback(
                    provider, headers, body));
        } catch (RuntimeException invalid) {
            metrics.webhook("rejected");
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "WEBHOOK_VERIFICATION_FAILED");
        }
        if (receipt == null || !receipt.verified()
                || !provider.equals(receipt.providerType())) {
            metrics.webhook("rejected");
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "WEBHOOK_VERIFICATION_FAILED");
        }
        return receipt;
    }

    @Override
    public MailWebhookIngressUseCase.Accepted acceptVerified(
            MailWebhookVerificationPort.Verification receipt) {
        if (receipt == null || !receipt.verified()) {
            metrics.webhook("rejected");
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "WEBHOOK_VERIFICATION_FAILED");
        }
        MailSyncJobRepository.WebhookEnqueue accepted = syncJobs
                .enqueueVerifiedWebhook(receipt, maximumAttempts);
        metrics.webhook("accepted");
        return new MailWebhookIngressUseCase.Accepted(
                accepted.operationId(), accepted.jobId());
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
