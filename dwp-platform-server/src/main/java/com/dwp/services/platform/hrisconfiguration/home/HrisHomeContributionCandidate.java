package com.dwp.services.platform.hrisconfiguration.home;

import com.dwp.contracts.hris.xcon.v1.Xcon012PerformanceHomeContributionChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon013PeopleHomeContributionChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon014TimeLeaveHomeContributionChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon015PayrollHomeContributionChangedV1;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Tenant-bound projection of the four canonical Home change events.
 *
 * <p>The event payload intentionally has no tenant or subject. The trusted event envelope tenant
 * is therefore a separate required constructor input and is never inferred from an owner body.
 */
public record HrisHomeContributionCandidate(
        UUID eventId,
        long tenantId,
        HrisHomeContributionMaterializationV1.Module module,
        String widgetKey,
        HrisHomeContributionMaterializationV1.OwnerPopulationScope ownerPopulationScope,
        long scopeRevision,
        HrisHomeContributionMaterializationV1.State state,
        Instant generatedAt,
        Instant freshUntil,
        Instant staleAfter,
        String payloadRef,
        String payloadDigest) {

    public HrisHomeContributionCandidate {
        Objects.requireNonNull(eventId, "eventId");
        if (tenantId < 1 || scopeRevision < 1) {
            throw new IllegalArgumentException("candidate tenant and scope revision must be positive");
        }
        Objects.requireNonNull(module, "module");
        if (widgetKey == null || widgetKey.isBlank()) {
            throw new IllegalArgumentException("widgetKey is required");
        }
        Objects.requireNonNull(ownerPopulationScope, "ownerPopulationScope");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(freshUntil, "freshUntil");
        Objects.requireNonNull(staleAfter, "staleAfter");
        if (generatedAt.isAfter(freshUntil) || freshUntil.isAfter(staleAfter)) {
            throw new IllegalArgumentException("candidate freshness window is invalid");
        }
        if (payloadRef == null || !payloadRef.matches("^href_[A-Za-z0-9_-]{16,128}$")
                || payloadDigest == null || !payloadDigest.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("candidate payload reference is invalid");
        }
    }

    public static HrisHomeContributionCandidate from(
            UUID eventId, long tenantId, Xcon013PeopleHomeContributionChangedV1 event) {
        return candidate(eventId, tenantId, event.module(), event.widgetKey(),
                event.ownerPopulationScope(), event.scopeRevision(), event.state(),
                event.generatedAt(), event.freshUntil(), event.staleAfter(),
                event.payloadRef(), event.payloadDigest());
    }

    public static HrisHomeContributionCandidate from(
            UUID eventId, long tenantId, Xcon012PerformanceHomeContributionChangedV1 event) {
        return candidate(eventId, tenantId, event.module(), event.widgetKey(),
                event.ownerPopulationScope(), event.scopeRevision(), event.state(),
                event.generatedAt(), event.freshUntil(), event.staleAfter(),
                event.payloadRef(), event.payloadDigest());
    }

    public static HrisHomeContributionCandidate from(
            UUID eventId, long tenantId, Xcon014TimeLeaveHomeContributionChangedV1 event) {
        return candidate(eventId, tenantId, event.module(), event.widgetKey(),
                event.ownerPopulationScope(), event.scopeRevision(), event.state(),
                event.generatedAt(), event.freshUntil(), event.staleAfter(),
                event.payloadRef(), event.payloadDigest());
    }

    public static HrisHomeContributionCandidate from(
            UUID eventId, long tenantId, Xcon015PayrollHomeContributionChangedV1 event) {
        return candidate(eventId, tenantId, event.module(), event.widgetKey(),
                event.ownerPopulationScope(), event.scopeRevision(), event.state(),
                event.generatedAt(), event.freshUntil(), event.staleAfter(),
                event.payloadRef(), event.payloadDigest());
    }

    private static HrisHomeContributionCandidate candidate(
            UUID eventId,
            long tenantId,
            String module,
            String widgetKey,
            String ownerPopulationScope,
            long scopeRevision,
            String state,
            Instant generatedAt,
            Instant freshUntil,
            Instant staleAfter,
            String payloadRef,
            String payloadDigest) {
        return new HrisHomeContributionCandidate(
                eventId,
                tenantId,
                HrisHomeContributionMaterializationV1.Module.valueOf(module),
                widgetKey,
                HrisHomeContributionMaterializationV1.OwnerPopulationScope.valueOf(
                        ownerPopulationScope),
                scopeRevision,
                HrisHomeContributionMaterializationV1.State.valueOf(state),
                generatedAt,
                freshUntil,
                staleAfter,
                payloadRef,
                payloadDigest);
    }
}
