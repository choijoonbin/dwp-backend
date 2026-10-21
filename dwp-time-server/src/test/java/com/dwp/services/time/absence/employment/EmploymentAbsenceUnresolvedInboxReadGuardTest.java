package com.dwp.services.time.absence.employment;

import com.dwp.contracts.hris.xcon.v1.Xcon027EmploymentAbsenceSnapshotV1;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EmploymentAbsenceUnresolvedInboxReadGuardTest {

    @Test
    void returnsOnlyCurrentUnexpiredCurrentDateFactsWithNoUnresolvedInbox() {
        var signed = EmploymentAbsenceTestSupport.signedOwner(0, 0);
        var store = store(projection(signed));
        CountingScheduler scheduler = new CountingScheduler();
        EmploymentAbsenceReadGuard guard = guard(store, signed.key(), scheduler,
                EmploymentAbsenceTestSupport.CLOCK);

        var facts = guard.current(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                LocalDate.of(2026, 10, 21));

        assertThat(facts).isPresent();
        assertThat(facts.orElseThrow().snapshot().workerPublicId())
                .isEqualTo(EmploymentAbsenceTestSupport.WORKER);
        assertThat(scheduler.refreshes).hasValue(0);
    }

    @Test
    void unresolvedInboxAndPastOrFutureDateReturnZeroFacts() {
        var signed = EmploymentAbsenceTestSupport.signedOwner(0, 0);
        var store = store(projection(signed));
        store.unresolved = true;
        CountingScheduler scheduler = new CountingScheduler();
        EmploymentAbsenceReadGuard guard = guard(store, signed.key(), scheduler,
                EmploymentAbsenceTestSupport.CLOCK);

        assertThat(guard.current(EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                LocalDate.of(2026, 10, 21))).isEmpty();
        store.unresolved = false;
        assertThat(guard.current(EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                LocalDate.of(2026, 10, 20))).isEmpty();
        assertThat(guard.current(EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                LocalDate.of(2026, 10, 22))).isEmpty();
        assertThat(scheduler.refreshes).hasValue(1);
    }

    @Test
    void digestCorruptionIsCasQuarantinedBeforeFactsReturn() {
        var signed = EmploymentAbsenceTestSupport.signedOwner(0, 0);
        EmploymentAbsenceProjection good = projection(signed);
        Xcon027EmploymentAbsenceSnapshotV1 value = good.ownerSnapshot();
        Xcon027EmploymentAbsenceSnapshotV1 corrupt =
                new Xcon027EmploymentAbsenceSnapshotV1(
                        value.relationshipPublicId(), value.relationshipRevision(),
                        value.workerPublicId(), value.workerStatus(),
                        value.relationshipType(), value.relationshipStatus(),
                        value.legalEntityPublicId(), value.relationshipEffectiveFrom(),
                        value.relationshipEffectiveTo(), value.effectiveOn(),
                        value.assignments(), value.ownerRevision(), value.asOf(),
                        value.validUntil(), value.ownerContractId(), value.ownerService(),
                        value.purposeCode(), value.scopeDigest(), "f".repeat(64));
        EmploymentAbsenceProjection corruptedProjection =
                new EmploymentAbsenceProjection(
                        good.tenantId(), good.relationshipPublicId(), good.freshness(),
                        good.lastObservedEventSequence(),
                        good.requiredRelationshipRevision(), good.sharedOffsetSequence(),
                        good.gapExpectedSequence(), corrupt, good.ownerEvidence(),
                        good.ownerBusinessTimeZone(),
                        good.ownerMinimumRelationshipRevision(), good.lastInvalidation(),
                        good.recoveryOutcome(), good.lastRecoveryErrorCode(),
                        good.lastRecoveryAt(), good.projectionVersion(),
                        good.createdAt(), good.updatedAt());
        var store = store(corruptedProjection);
        CountingScheduler scheduler = new CountingScheduler();
        EmploymentAbsenceReadGuard guard = guard(store, signed.key(), scheduler,
                EmploymentAbsenceTestSupport.CLOCK);

        assertThat(guard.current(EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                LocalDate.of(2026, 10, 21))).isEmpty();
        assertThat(store.find(EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP).orElseThrow().freshness())
                .isEqualTo(EmploymentAbsenceContract.FreshnessState.QUARANTINED);
        assertThat(scheduler.refreshes).hasValue(1);
    }

    @Test
    void eventlessPlaceholderRoutesToBaselineInsteadOfCurrentRefresh() {
        var signed = EmploymentAbsenceTestSupport.signedOwner(0, 0);
        var store = new EmploymentAbsenceTestSupport.InMemoryProjectionStore();
        store.insertExpectedZero(EmploymentAbsenceProjection.baselineClaim(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                EmploymentAbsenceTestSupport.NOW.minusSeconds(60)));
        CountingScheduler scheduler = new CountingScheduler();
        EmploymentAbsenceReadGuard guard = guard(store, signed.key(), scheduler,
                EmploymentAbsenceTestSupport.CLOCK);

        assertThat(guard.current(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                LocalDate.of(2026, 10, 21))).isEmpty();
        assertThat(scheduler.baselines).hasValue(1);
        assertThat(scheduler.refreshes).hasValue(0);
    }

    @Test
    void mapperLevelCorruptionIsCasQuarantinedAndReturnsNoFacts() {
        var signed = EmploymentAbsenceTestSupport.signedOwner(0, 0);
        var store = store(projection(signed));
        store.corruptRead = true;
        CountingScheduler scheduler = new CountingScheduler();
        EmploymentAbsenceReadGuard guard = guard(store, signed.key(), scheduler,
                EmploymentAbsenceTestSupport.CLOCK);

        assertThat(guard.current(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                LocalDate.of(2026, 10, 21))).isEmpty();

        EmploymentAbsenceProjection quarantined = store.find(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP).orElseThrow();
        assertThat(quarantined.freshness())
                .isEqualTo(EmploymentAbsenceContract.FreshnessState.QUARANTINED);
        assertThat(quarantined.lastRecoveryErrorCode())
                .isEqualTo("STORED_PROJECTION_INTEGRITY_FAILURE");
        assertThat(scheduler.refreshes).hasValue(1);
    }

    @Test
    void responseKeyAuthorityOutageReturnsZeroFactsWithoutQuarantiningProjection() {
        var signed = EmploymentAbsenceTestSupport.signedOwner(0, 0);
        EmploymentAbsenceProjection before = projection(signed);
        var store = store(before);
        CountingScheduler scheduler = new CountingScheduler();
        EmploymentAbsenceReadGuard guard = guard(
                store,
                tenant -> Optional.of(EmploymentAbsenceTestSupport.grant()),
                tenant -> Optional.of(EmploymentAbsenceTestSupport.context()),
                keyId -> { throw new IllegalStateException("key authority unavailable"); },
                scheduler, EmploymentAbsenceTestSupport.CLOCK);

        assertThat(guard.current(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                LocalDate.of(2026, 10, 21))).isEmpty();
        assertThat(store.find(EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP).orElseThrow())
                .isEqualTo(before);
        assertThat(scheduler.refreshes).hasValue(1);
    }

    @Test
    void currentAuthorityOutageReturnsZeroFactsWithoutMutatingProjection() {
        var signed = EmploymentAbsenceTestSupport.signedOwner(0, 0);
        EmploymentAbsenceProjection before = projection(signed);
        var store = store(before);
        CountingScheduler scheduler = new CountingScheduler();
        EmploymentAbsenceReadGuard guard = guard(
                store,
                tenant -> { throw new IllegalStateException("grant authority unavailable"); },
                tenant -> Optional.of(EmploymentAbsenceTestSupport.context()),
                keyId -> Optional.of(signed.key()), scheduler,
                EmploymentAbsenceTestSupport.CLOCK);

        assertThat(guard.current(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                LocalDate.of(2026, 10, 21))).isEmpty();
        assertThat(store.find(EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP).orElseThrow())
                .isEqualTo(before);
        assertThat(scheduler.refreshes).hasValue(1);
    }

    private static EmploymentAbsenceProjection projection(
            EmploymentAbsenceTestSupport.SignedOwner signed) {
        return new EmploymentAbsenceProjection(
                EmploymentAbsenceTestSupport.TENANT,
                EmploymentAbsenceTestSupport.RELATIONSHIP,
                EmploymentAbsenceContract.FreshnessState.CURRENT,
                0, 0, 0, null, signed.success().data(),
                EmploymentAbsenceProjection.StoredOwnerEvidence.from(
                        signed.success().evidence()),
                "UTC", 0, null, EmploymentAbsenceContract.RecoveryOutcome.SUCCEEDED,
                null, EmploymentAbsenceTestSupport.NOW, 1,
                EmploymentAbsenceTestSupport.NOW.minusSeconds(1),
                EmploymentAbsenceTestSupport.NOW);
    }

    private static EmploymentAbsenceTestSupport.InMemoryProjectionStore store(
            EmploymentAbsenceProjection projection) {
        var store = new EmploymentAbsenceTestSupport.InMemoryProjectionStore();
        store.put(projection);
        return store;
    }

    private static EmploymentAbsenceReadGuard guard(
            EmploymentAbsenceProjectionStore store,
            EmploymentAbsenceContract.ResponseSigningKey key,
            CountingScheduler scheduler,
            java.time.Clock clock) {
        return guard(store,
                tenant -> Optional.of(EmploymentAbsenceTestSupport.grant()),
                tenant -> Optional.of(EmploymentAbsenceTestSupport.context()),
                keyId -> Optional.of(key), scheduler, clock);
    }

    private static EmploymentAbsenceReadGuard guard(
            EmploymentAbsenceProjectionStore store,
            EmploymentAbsenceContract.OwnerGrantAuthority grants,
            EmploymentAbsenceContract.TenantContextAuthority contexts,
            EmploymentAbsenceContract.ResponseKeyAuthority keys,
            CountingScheduler scheduler,
            java.time.Clock clock) {
        return new EmploymentAbsenceReadGuard(
                store, grants, contexts, keys, scheduler,
                (tenant, relationship, correlation, action, outcome, evidence) -> { },
                new EmploymentAbsenceActivationGate(true,
                        java.util.Set.of(EmploymentAbsenceTestSupport.TENANT)),
                EmploymentAbsenceTestSupport.mapper(),
                new EmploymentAbsenceTestSupport.NoopTransactionManager(), clock);
    }

    private static final class CountingScheduler
            implements EmploymentAbsenceRecoveryScheduler {
        private final AtomicInteger baselines = new AtomicInteger();
        private final AtomicInteger refreshes = new AtomicInteger();

        @Override
        public boolean scheduleBaseline(long tenantId, java.util.UUID relationshipPublicId) {
            baselines.incrementAndGet();
            return true;
        }

        @Override
        public boolean scheduleRefresh(
                long tenantId,
                java.util.UUID relationshipPublicId,
                String reason) {
            refreshes.incrementAndGet();
            return true;
        }
    }
}
