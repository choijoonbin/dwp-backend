package com.dwp.services.payroll.calculation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Tenant-scoped bounded dispatcher; leases remain the durable source of truth. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.payroll.calculation.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class PayrollCalculationDispatcher {

    private final PayrollCalculationStore store;
    private final PayrollCalculationWorker worker;
    private final Clock clock;
    private final List<Long> tenantIds;
    private final int maxClaimsPerPoll;
    private final String workerInstanceId;
    private final Duration leaseDuration;

    @Autowired
    PayrollCalculationDispatcher(
            PayrollCalculationStore store,
            PayrollCalculationWorker worker,
            @Value("${dwp.hris.payroll.calculation.worker.tenant-ids:}")
            String tenantIds,
            @Value("${dwp.hris.payroll.calculation.worker.max-claims-per-poll:8}")
            int maxClaimsPerPoll,
            @Value("${dwp.hris.payroll.calculation.worker.lease-duration:PT30S}")
            Duration leaseDuration) {
        this(store, worker, Clock.systemUTC(), parseTenantIds(tenantIds),
                maxClaimsPerPoll, "pay011-" + UUID.randomUUID(), leaseDuration);
    }

    PayrollCalculationDispatcher(
            PayrollCalculationStore store,
            PayrollCalculationWorker worker,
            Clock clock,
            List<Long> tenantIds,
            int maxClaimsPerPoll,
            String workerInstanceId,
            Duration leaseDuration) {
        this.store = store;
        this.worker = worker;
        this.clock = clock;
        this.tenantIds = List.copyOf(tenantIds);
        if (this.tenantIds.size() > 256 || this.tenantIds.stream().anyMatch(id -> id < 1)
                || maxClaimsPerPoll < 1 || maxClaimsPerPoll > 100
                || workerInstanceId == null || workerInstanceId.isBlank()
                || workerInstanceId.length() > 160
                || leaseDuration == null
                || leaseDuration.compareTo(Duration.ofMillis(100)) < 0
                || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Payroll dispatcher bounds are invalid");
        }
        this.maxClaimsPerPoll = maxClaimsPerPoll;
        this.workerInstanceId = workerInstanceId;
        this.leaseDuration = leaseDuration;
    }

    @Scheduled(
            fixedDelayString =
                    "${dwp.hris.payroll.calculation.worker.poll-delay:PT2S}",
            initialDelayString =
                    "${dwp.hris.payroll.calculation.worker.initial-delay:PT5S}")
    void poll() {
        pollOnce();
    }

    int pollOnce() {
        int dispatched = 0;
        for (long tenantId : tenantIds) {
            int remaining = maxClaimsPerPoll - dispatched;
            if (remaining == 0) break;
            List<PayrollCalculationStore.RecoveryCandidate> recoveries =
                    store.recoveryCandidates(tenantId, remaining);
            for (PayrollCalculationStore.RecoveryCandidate recovery : recoveries) {
                try {
                    worker.reconcile(recovery.tenantId(), recovery.runId());
                } catch (RuntimeException ignored) {
                    // The durable RESULT_UNKNOWN head remains discoverable for retry.
                }
                dispatched++;
                if (dispatched == maxClaimsPerPoll) return dispatched;
            }
            remaining = maxClaimsPerPoll - dispatched;
            if (remaining == 0) break;
            List<PayrollCalculationStore.ClaimableAttempt> attempts =
                    store.claimable(tenantId, remaining, workerInstanceId,
                            clock.instant(), leaseDuration);
            for (PayrollCalculationStore.ClaimableAttempt attempt : attempts) {
                try {
                    worker.execute(attempt.lease());
                } catch (RuntimeException ignored) {
                    // Durable checkpoints/leases determine retry or reconciliation.
                }
                dispatched++;
                if (dispatched == maxClaimsPerPoll) return dispatched;
            }
        }
        return dispatched;
    }

    private static List<Long> parseTenantIds(String value) {
        if (value == null || value.isBlank()) return List.of();
        Set<Long> result = new LinkedHashSet<>();
        Arrays.stream(value.split(",", -1)).forEach(token -> {
            if (token.isBlank() || !token.equals(token.strip())) {
                throw new IllegalArgumentException("Payroll worker tenant list is invalid");
            }
            try {
                long tenantId = Long.parseLong(token);
                if (tenantId < 1 || !result.add(tenantId)) {
                    throw new IllegalArgumentException(
                            "Payroll worker tenant list is invalid");
                }
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "Payroll worker tenant list is invalid", exception);
            }
        });
        return List.copyOf(result);
    }
}
