package com.dwp.services.auth.hris.separation.hrm013;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.scheduling.annotation.Scheduled;

/** Application boundary for identity-task admission, completion and receipt delivery. */
public final class Hrm013IdentityTaskDispatchService {
    private final Hrm013IdentityTaskDispatchStore store;
    private final TenantWorkSource tenants;
    private long tenantCursor;

    public Hrm013IdentityTaskDispatchService(Hrm013IdentityTaskDispatchStore store) {
        this(store, (after, limit) -> List.of());
    }

    public Hrm013IdentityTaskDispatchService(Hrm013IdentityTaskDispatchStore store,
            TenantWorkSource tenants) {
        this.store = Objects.requireNonNull(store, "store");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
    }

    public Hrm013IdentityTaskDispatchStore.Admission admit(
            Hrm013IdentityTaskDispatchIngressFilter.VerifiedTransportRequest request) {
        return store.admit(Objects.requireNonNull(request, "request"));
    }

    public Hrm013IdentityTaskDispatchStore.Completion complete(
            Hrm013IdentityTaskDispatchStore.CompletionCommand command) {
        return store.complete(Objects.requireNonNull(command, "command"));
    }

    public Optional<Hrm013IdentityTaskDispatchStore.ReceiptClaim> claimReceipt(
            long tenantId, Instant now) {
        return store.claimReceipt(tenantId, now);
    }

    public void receiptDelivered(
            Hrm013IdentityTaskDispatchStore.ReceiptClaim claim, Instant deliveredAt) {
        store.receiptDelivered(claim, deliveredAt);
    }

    public void receiptFailed(Hrm013IdentityTaskDispatchStore.ReceiptClaim claim,
            Instant now, String safeCode, Instant retryAt) {
        store.receiptFailed(claim, now, safeCode, retryAt);
    }

    public Hrm013IdentityTaskDispatchStore.AuthorizedReceiptAttempt
            authorizeReceiptAttempt(
                    Hrm013IdentityTaskDispatchStore.ReceiptClaim claim,
                    com.dwp.platform.contracts.hris.separation.v1
                            .Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 body) {
        return store.authorizeReceiptAttempt(claim, body);
    }

    public Hrm013IdentityTaskDispatchStore.AuthorizedReceiptAttempt
            recheckReceiptAttempt(
            Hrm013IdentityTaskDispatchStore.AuthorizedReceiptAttempt attempt) {
        return store.recheckReceiptAttempt(attempt);
    }

    /** Bounded default-OFF worker entry point; at most one due task per tenant. */
    public int executeScheduledBatch() {
        List<Long> tenantIds = nextTenantPage();
        int completed = 0;
        for (long tenantId : tenantIds) {
            try {
                if (store.executeNext(tenantId).isPresent()) completed++;
            } catch (RuntimeException ignored) {
                // The durable row remains RECEIVED after rollback; another poll retries it.
            }
        }
        return completed;
    }

    private synchronized List<Long> nextTenantPage() {
        List<Long> page = boundedTenants(tenants.pageAfter(tenantCursor, 100));
        if (page.isEmpty() && tenantCursor != 0L) {
            tenantCursor = 0L;
            page = boundedTenants(tenants.pageAfter(0L, 100));
        }
        if (!page.isEmpty()) tenantCursor = page.getLast();
        return page;
    }

    @Scheduled(fixedDelayString =
            "${dwp.auth.hrm013-identity-task-dispatch.executor-poll-delay-ms:5000}")
    void executeScheduled() {
        executeScheduledBatch();
    }

    static List<Long> boundedTenants(List<Long> values) {
        if (values == null || values.size() > 100) {
            throw new IllegalStateException("bounded HRM-013 tenant source required");
        }
        List<Long> copy = List.copyOf(values);
        if (copy.stream().anyMatch(value -> value == null || value < 1)
                || copy.stream().distinct().count() != copy.size()) {
            throw new IllegalStateException("canonical HRM-013 tenant source required");
        }
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1) >= copy.get(index)) {
                throw new IllegalStateException("ordered HRM-013 tenant source required");
            }
        }
        return copy;
    }

    @FunctionalInterface
    public interface TenantWorkSource {
        List<Long> pageAfter(long tenantIdExclusive, int limit);
    }
}
