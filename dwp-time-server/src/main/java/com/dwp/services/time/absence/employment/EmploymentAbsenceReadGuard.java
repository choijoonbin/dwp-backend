package com.dwp.services.time.absence.employment;

import com.dwp.contracts.hris.xcon.v1.Xcon027EmploymentAbsenceSnapshotV1;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.OwnerGrant;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.OwnerFailure;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Current-date-only fail-closed employment fact guard used by absence evaluation. */
public final class EmploymentAbsenceReadGuard {

    private final EmploymentAbsenceProjectionStore projections;
    private final EmploymentAbsenceContract.OwnerGrantAuthority grants;
    private final EmploymentAbsenceContract.TenantContextAuthority contexts;
    private final EmploymentAbsenceContract.ResponseKeyAuthority keys;
    private final EmploymentAbsenceRecoveryScheduler scheduler;
    private final EmploymentAbsenceAuditSink audit;
    private final EmploymentAbsenceActivationGate gate;
    private final EmploymentAbsenceCanonicalJson canonical;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public EmploymentAbsenceReadGuard(
            EmploymentAbsenceProjectionStore projections,
            EmploymentAbsenceContract.OwnerGrantAuthority grants,
            EmploymentAbsenceContract.TenantContextAuthority contexts,
            EmploymentAbsenceContract.ResponseKeyAuthority keys,
            EmploymentAbsenceRecoveryScheduler scheduler,
            EmploymentAbsenceAuditSink audit,
            EmploymentAbsenceActivationGate gate,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.projections = projections;
        this.grants = grants;
        this.contexts = contexts;
        this.keys = keys;
        this.scheduler = scheduler;
        this.audit = audit;
        this.gate = gate;
        this.canonical = new EmploymentAbsenceCanonicalJson(mapper);
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public Optional<EmploymentFacts> current(
            long tenantId,
            UUID relationshipPublicId,
            LocalDate requestedDate) {
        if (!gate.permits(tenantId)) return Optional.empty();
        Instant now = clock.instant();
        Optional<TenantContext> context;
        Optional<OwnerGrant> grant;
        try {
            context = contexts.current(tenantId);
            grant = grants.current(tenantId).filter(OwnerGrant::active);
        } catch (RuntimeException unavailable) {
            scheduler.scheduleRefresh(tenantId, relationshipPublicId,
                    "OWNER_AUTHORITY_UNAVAILABLE");
            return Optional.empty();
        }
        if (context.isEmpty() || grant.isEmpty()
                || !context.get().matches(grant.get())) {
            scheduler.scheduleRefresh(tenantId, relationshipPublicId,
                    "OWNER_AUTHORITY_UNAVAILABLE");
            return Optional.empty();
        }
        if (!requestedDate.equals(context.get().currentDate(now))) {
            return Optional.empty();
        }

        ReadCandidate candidate;
        try {
            candidate = transactions.execute(status ->
                    readCandidate(tenantId, relationshipPublicId));
        } catch (JdbcEmploymentAbsenceProjectionStore.ProjectionCasLost stale) {
            scheduler.scheduleRefresh(tenantId, relationshipPublicId,
                    "STORED_PROJECTION_INTEGRITY_FAILURE");
            return Optional.empty();
        }
        if (candidate == null || candidate.corrupt()) {
            scheduler.scheduleRefresh(tenantId, relationshipPublicId,
                    "STORED_PROJECTION_INTEGRITY_FAILURE");
            return Optional.empty();
        }
        if (candidate.projection().isEmpty()) {
            if (candidate != null && candidate.unresolvedInbox()) {
                scheduler.scheduleRefresh(tenantId, relationshipPublicId,
                        "UNRESOLVED_INBOX");
            } else {
                scheduler.scheduleBaseline(tenantId, relationshipPublicId);
            }
            return Optional.empty();
        }
        EmploymentAbsenceProjection projection = candidate.projection().orElseThrow();
        boolean unresolved = candidate.unresolvedInbox();
        if (!unresolved && projection.isOwnerBaselinePlaceholder()) {
            scheduler.scheduleBaseline(tenantId, relationshipPublicId);
            return Optional.empty();
        }
        if (!projection.coveredAndReadable(requestedDate, now, grant.get(),
                context.get(), unresolved)) {
            scheduler.scheduleRefresh(tenantId, relationshipPublicId,
                    unresolved ? "UNRESOLVED_INBOX" : "STALE_OR_AUTHORITY_MISMATCH");
            return Optional.empty();
        }
        try {
            canonical.validateGeneratedSnapshot(projection.ownerSnapshot());
            if (!projection.ownerSnapshot().contentSha256().equals(
                    canonical.contentDigest(projection.ownerSnapshot()))
                    || !projection.ownerSnapshot().scopeDigest().equals(
                    canonical.storedScopeDigest(projection, grant.get()))
                    || projection.ownerEvidence() == null) {
                throw new IllegalArgumentException("stored owner evidence is corrupt");
            }
        } catch (RuntimeException corrupt) {
            quarantineCorrupt(projection);
            scheduler.scheduleRefresh(tenantId, relationshipPublicId,
                    "STORED_PROJECTION_INTEGRITY_FAILURE");
            return Optional.empty();
        }
        try {
            if (keys.current(projection.ownerEvidence().keyId())
                    .filter(key -> key.keyId().equals(
                            projection.ownerEvidence().keyId()))
                    .filter(EmploymentAbsenceContract.ResponseSigningKey::active)
                    .isEmpty()) {
                scheduler.scheduleRefresh(tenantId, relationshipPublicId,
                        "OWNER_KEY_AUTHORITY_UNAVAILABLE");
                return Optional.empty();
            }
        } catch (RuntimeException unavailable) {
            scheduler.scheduleRefresh(tenantId, relationshipPublicId,
                    "OWNER_KEY_AUTHORITY_UNAVAILABLE");
            return Optional.empty();
        }
        Boolean stable = transactions.execute(status -> {
            ReadCandidate fenced = readCandidate(tenantId, relationshipPublicId);
            return !fenced.corrupt()
                    && !fenced.unresolvedInbox()
                    && fenced.projection().isPresent()
                    && fenced.projection().orElseThrow().projectionVersion()
                        == projection.projectionVersion();
        });
        if (!Boolean.TRUE.equals(stable)) {
            scheduler.scheduleRefresh(tenantId, relationshipPublicId,
                    "READ_FENCE_CHANGED");
            return Optional.empty();
        }
        return Optional.of(new EmploymentFacts(
                tenantId, relationshipPublicId, projection.ownerSnapshot()));
    }

    private ReadCandidate readCandidate(long tenantId, UUID relationshipPublicId) {
        projections.lockTuple(tenantId, relationshipPublicId);
        EmploymentAbsenceProjectionStore.EvaluationRead read =
                projections.readForEvaluation(tenantId, relationshipPublicId);
        boolean unresolved = projections.hasUnresolvedInbox(
                tenantId, relationshipPublicId);
        if (read instanceof EmploymentAbsenceProjectionStore.EvaluationRead.Corrupt corrupt) {
            EmploymentAbsenceProjectionStore.CorruptionQuarantine quarantined =
                    projections.quarantineCorruptExpectedVersion(
                            tenantId, relationshipPublicId,
                            corrupt.projectionVersion(), clock.instant());
            if (quarantined.changed()) {
                audit.append(tenantId, relationshipPublicId, UUID.randomUUID(),
                        "TIM003_STORED_PROJECTION_INTEGRITY_FAILURE", "DENIED",
                        Map.of("projectionExpectedVersion", corrupt.projectionVersion(),
                                "projectionResultVersion",
                                quarantined.projectionVersion()));
            }
            return new ReadCandidate(Optional.empty(), unresolved, true);
        }
        Optional<EmploymentAbsenceProjection> projection =
                read instanceof EmploymentAbsenceProjectionStore.EvaluationRead.Available available
                        ? Optional.of(available.projection()) : Optional.empty();
        return new ReadCandidate(projection, unresolved, false);
    }

    private void quarantineCorrupt(EmploymentAbsenceProjection projection) {
        transactions.executeWithoutResult(status -> {
            projections.lockTuple(
                    projection.tenantId(), projection.relationshipPublicId());
            EmploymentAbsenceProjection current = projections.find(
                            projection.tenantId(), projection.relationshipPublicId())
                    .orElseThrow();
            if (current.projectionVersion() != projection.projectionVersion()) return;
            EmploymentAbsenceProjection quarantined =
                    current.quarantineStoredIntegrityFailure(clock.instant());
            EmploymentAbsenceProjection stored = projections.updateExpectedVersion(
                    quarantined, current.projectionVersion());
            audit.append(current.tenantId(), current.relationshipPublicId(),
                    UUID.randomUUID(),
                    "TIM003_STORED_PROJECTION_INTEGRITY_FAILURE", "DENIED",
                    Map.of("projectionVersion", stored.projectionVersion()));
        });
    }

    private record ReadCandidate(
            Optional<EmploymentAbsenceProjection> projection,
            boolean unresolvedInbox,
            boolean corrupt) {
    }

    public record EmploymentFacts(
            long tenantId,
            UUID relationshipPublicId,
            Xcon027EmploymentAbsenceSnapshotV1 snapshot) {
    }
}
