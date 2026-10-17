package com.dwp.services.payroll.input.closedtime;

import java.util.Objects;
import java.util.Set;

/**
 * Framework-neutral default-OFF guard for the non-durable consumer seam.
 * No production listener or dependency-injection binding is supplied here.
 */
public final class ClosedTimeResultConsumerGate {

    private final boolean enabled;
    private final Set<Long> enrolledTenantIds;
    private final ClosedTimeResultConsumerPort consumer;

    public ClosedTimeResultConsumerGate(
            Boolean enabled,
            Set<Long> enrolledTenantIds,
            ClosedTimeResultConsumerPort consumer) {
        this.enabled = Boolean.TRUE.equals(enabled);
        this.enrolledTenantIds = Set.copyOf(
                Objects.requireNonNull(enrolledTenantIds, "enrolledTenantIds"));
        if (this.enrolledTenantIds.stream().anyMatch(tenantId -> tenantId <= 0)) {
            throw new IllegalArgumentException(
                    "enrolledTenantIds must contain only positive values");
        }
        this.consumer = Objects.requireNonNull(consumer, "consumer");
    }

    public boolean accept(
            ClosedTimeResultContractVerifier.VerifiedHandoff handoff) {
        Objects.requireNonNull(handoff, "handoff");
        if (!allows(handoff.tenantId())) {
            return false;
        }
        consumer.accept(handoff);
        return true;
    }

    public boolean invalidate(
            ClosedTimeResultContractVerifier.VerifiedReopen reopen) {
        Objects.requireNonNull(reopen, "reopen");
        if (!allows(reopen.tenantId())) {
            return false;
        }
        consumer.invalidate(reopen);
        return true;
    }

    public boolean allows(long tenantId) {
        return enabled && enrolledTenantIds.contains(tenantId);
    }
}
