package com.dwp.services.notification.integration;

import com.dwp.services.notification.domain.DirectNotificationMaterializer;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Each checkpoint, child intent and retention admission commit together, or none of that chunk does. */
public final class ApprovalSlaNotificationConsumer {
    private final TransactionTemplate transactions;
    private final NotificationDatabaseScope scope;
    private final ApprovalSlaDeliveryJournal journal;
    private final ApprovalSlaRecipientAuthorityClient authority;
    private final DirectNotificationMaterializer materializer;

    public ApprovalSlaNotificationConsumer(PlatformTransactionManager manager, NotificationDatabaseScope scope,
            ApprovalSlaDeliveryJournal journal, ApprovalSlaRecipientAuthorityClient authority,
            DirectNotificationMaterializer materializer) {
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(30);
        this.scope = Objects.requireNonNull(scope); this.journal = Objects.requireNonNull(journal);
        this.authority = Objects.requireNonNull(authority); this.materializer = Objects.requireNonNull(materializer);
    }

    public void deliver(ApprovalSlaNotificationPlan plan) {
        Objects.requireNonNull(plan);
        boolean finished = Boolean.TRUE.equals(transactions.execute(status -> {
            scope.applyWorker(plan.actor().tenantId()); return journal.finished(plan);
        }));
        if (finished) return;
        UUID owner = UUID.randomUUID();
        for (int index = 0; index < plan.chunkCount(); index++) {
            final int chunk = index;
            transactions.executeWithoutResult(status -> {
                scope.applyWorker(plan.actor().tenantId());
                // Verify before inserting a new delivery identity; unauthenticated input cannot reserve an event ID.
                var before = authority.evaluate(plan); before.requireCurrent(plan);
                var lease = journal.claim(plan, owner, Duration.ofSeconds(30));
                if (lease.finished() || journal.hasChunk(lease, chunk)) return;
                var seats = plan.chunk(chunk);
                var eligible = before.eligibleUserIds();
                var requests = seats.stream().filter(seat -> eligible.contains(seat.userId())).map(plan::request).toList();
                var results = new HashMap<Long, com.dwp.services.notification.domain.NotificationModels.MaterializationResult>();
                if (!requests.isEmpty()) {
                    var materialized = materializer.materializeApprovalSlaWithinWorkerTransaction(plan.actor(), plan, requests, before);
                    if (materialized.size() != requests.size()) throw new IllegalStateException("Incomplete SLA materialization.");
                    for (int position = 0; position < requests.size(); position++)
                        results.put(requests.get(position).recipientUserIds().getFirst(), materialized.get(position));
                }
                var after = authority.evaluate(plan); before.requireSameCurrent(after);
                var outcomes = new ArrayList<ApprovalSlaDeliveryJournal.Outcome>();
                for (var seat : seats) {
                    var result = results.get(seat.userId());
                    if (eligible.contains(seat.userId()) && (result == null || result.intentId() == null))
                        throw new IllegalStateException("Missing SLA child intent.");
                    outcomes.add(new ApprovalSlaDeliveryJournal.Outcome(seat, eligible.contains(seat.userId()),
                            result == null ? null : result.intentId(), result == null ? null : result.notificationId()));
                }
                journal.completeChunk(lease, chunk, outcomes, after.stableDigest());
                after.requireCurrent(plan);
            });
        }
        transactions.executeWithoutResult(status -> {
            scope.applyWorker(plan.actor().tenantId());
            var current = authority.evaluate(plan); current.requireCurrent(plan);
            var lease = journal.claim(plan, owner, Duration.ofSeconds(30));
            if (!lease.finished()) { current.requireCurrent(plan); journal.finish(lease); }
        });
    }
}
