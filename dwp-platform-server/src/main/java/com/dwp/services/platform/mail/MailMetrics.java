package com.dwp.services.platform.mail;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/** Low-cardinality operational signals for Mail's durable workers. */
@Component
class MailMetrics {

    private static final Set<String> DELIVERY_OUTCOMES = Set.of(
            "delivered", "provider_accepted", "result_unknown",
            "retry_wait", "failed");
    private static final Set<String> SYNC_OUTCOMES = Set.of("succeeded", "failed", "partial");
    private static final Set<String> SYNC_JOB_OUTCOMES = Set.of(
            "succeeded", "continued", "retry_wait", "dead_letter", "poll_failed");
    private static final Set<String> WEBHOOK_OUTCOMES = Set.of(
            "accepted", "rejected", "unavailable", "ambiguous");
    private static final Set<String> EVENT_STREAM_OUTCOMES = Set.of(
            "connected", "completed", "timeout", "error", "rejected",
            "heartbeat", "event", "authority_expired");
    private static final Set<String> SCANNER_OUTCOMES = Set.of(
            "clean", "rejected", "invalid", "timeout", "unavailable", "error");
    private static final Set<String> SUBSCRIPTION_OUTCOMES = Set.of(
            "scheduled", "renewed", "retry_wait", "failed", "expired",
            "lease_lost", "poll_failed");
    private static final Set<String> INCOMING_RULE_OUTCOMES = Set.of(
            "changed", "matched", "unmatched", "retry", "failed");
    private static final Set<String> PURGE_OUTCOMES = Set.of(
            "succeeded", "partial", "failed", "unknown", "poll_failed");

    private final MeterRegistry registry;

    MailMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void delivery(MailTypes.ProviderType provider, String outcome) {
        afterCommit(() -> registry.counter(
                "dwp.mail.delivery.outcomes",
                "provider", provider == null ? "unknown" : provider.name().toLowerCase(Locale.ROOT),
                "outcome", bounded(outcome, DELIVERY_OUTCOMES)).increment());
    }

    void sync(MailExternalSyncMaterializer.SyncResult result, Duration duration) {
        String outcome = result.partial() ? "partial" : "succeeded";
        afterCommit(() -> {
            registry.counter("dwp.mail.sync.batches", "outcome", outcome).increment();
            registry.counter("dwp.mail.sync.messages", "outcome", "inserted")
                    .increment(result.inserted());
            registry.counter("dwp.mail.sync.messages", "outcome", "duplicate")
                    .increment(result.duplicate());
            registry.counter("dwp.mail.sync.attachments", "outcome", "blocked")
                    .increment(result.blockedAttachments());
            Timer.builder("dwp.mail.sync.duration")
                    .tag("outcome", outcome)
                    .register(registry)
                    .record(duration);
        });
    }

    void syncFailure(Duration duration) {
        registry.counter("dwp.mail.sync.batches", "outcome", "failed").increment();
        Timer.builder("dwp.mail.sync.duration")
                .tag("outcome", "failed")
                .register(registry)
                .record(duration);
    }

    void syncJob(String outcome, Duration duration) {
        String bounded = bounded(outcome, SYNC_JOB_OUTCOMES);
        registry.counter("dwp.mail.sync.jobs", "outcome", bounded).increment();
        Timer.builder("dwp.mail.sync.jobs.duration")
                .tag("outcome", bounded)
                .register(registry)
                .record(duration);
    }

    void syncLeaseRecovery(MailSyncJobRepository.LeaseRecovery recovery) {
        if (recovery == null) return;
        registry.counter("dwp.mail.sync.jobs", "outcome", "retry_wait")
                .increment(recovery.retryWait());
        registry.counter("dwp.mail.sync.jobs", "outcome", "dead_letter")
                .increment(recovery.deadLetter());
    }

    void webhook(String outcome) {
        registry.counter("dwp.mail.webhooks", "outcome",
                bounded(outcome, WEBHOOK_OUTCOMES)).increment();
    }

    void eventStream(String outcome) {
        registry.counter("dwp.mail.events.stream", "outcome",
                bounded(outcome, EVENT_STREAM_OUTCOMES)).increment();
    }

    void eventCatchUp(int events) {
        registry.counter("dwp.mail.events.catchup", "outcome", "requested").increment();
        registry.summary("dwp.mail.events.catchup.size").record(Math.max(0, events));
    }

    void scanner(String outcome, Duration duration) {
        String bounded = bounded(outcome, SCANNER_OUTCOMES);
        registry.counter("dwp.mail.attachment.scan", "outcome", bounded).increment();
        Timer.builder("dwp.mail.attachment.scan.duration")
                .tag("outcome", bounded)
                .register(registry)
                .record(duration);
    }

    void subscription(String outcome, int count) {
        registry.counter("dwp.mail.subscriptions", "outcome",
                bounded(outcome, SUBSCRIPTION_OUTCOMES)).increment(Math.max(0, count));
    }

    void followUp(MailFollowUpDueNotificationTransactions.BatchResult result) {
        registry.counter("dwp.mail.followup.batches", "outcome", "succeeded").increment();
        registry.counter("dwp.mail.followup.notifications", "outcome", "emitted")
                .increment(result.emitted());
        registry.counter("dwp.mail.followup.notifications", "outcome", "suppressed")
                .increment(result.suppressed());
    }

    void followUpFailure() {
        registry.counter("dwp.mail.followup.batches", "outcome", "failed").increment();
    }

    void purge(String outcome) {
        registry.counter("dwp.mail.purge.jobs",
                "outcome", bounded(outcome, PURGE_OUTCOMES)).increment();
    }

    void incomingRule(String outcome) {
        afterCommit(() -> registry.counter("dwp.mail.incoming.rules",
                "outcome", bounded(outcome, INCOMING_RULE_OUTCOMES)).increment());
    }

    private void afterCommit(Runnable observation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            observation.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        observation.run();
                    }
                });
    }

    private String bounded(String value, Set<String> allowed) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : "unknown";
    }
}
