package com.dwp.services.people.hris.people;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.hris.contracts.workforce.v1.VerifiedWorkforceSnapshotCursor;
import com.dwp.services.people.hris.contracts.workforce.v1.VerifiedWorkforceSnapshotRequest;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotContract;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotPage;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQuery;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotRequestVerifier;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Supplier;

/** Resolves tenant and population authority owner-side; the consumer supplies neither. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "dwp.hris.performance.wave1.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class HrisWorkforceSnapshotAuthorityVerifier
        implements WorkforceSnapshotRequestVerifier {

    private final HcmPopulationScopeService populations;
    private final HrisWorkforceSnapshotCursorCodec cursors;
    private final Supplier<PeopleRequestContext.Actor> actorSupplier;
    private final Supplier<HcmPepContext.Evidence> evidenceSupplier;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    HrisWorkforceSnapshotAuthorityVerifier(
            HcmPopulationScopeService populations,
            HrisWorkforceSnapshotCursorCodec cursors) {
        this(populations, cursors, PeopleRequestContext::require,
                HcmPepContext::current, Clock.systemUTC());
    }

    HrisWorkforceSnapshotAuthorityVerifier(
            HcmPopulationScopeService populations,
            HrisWorkforceSnapshotCursorCodec cursors,
            Supplier<PeopleRequestContext.Actor> actorSupplier,
            Supplier<HcmPepContext.Evidence> evidenceSupplier,
            Clock clock) {
        this.populations = populations;
        this.cursors = cursors;
        this.actorSupplier = actorSupplier;
        this.evidenceSupplier = evidenceSupplier;
        this.clock = clock;
    }

    @Override
    public VerifiedWorkforceSnapshotRequest verify(WorkforceSnapshotQuery query) {
        if (query == null) throw unavailable("Workforce snapshot query is unavailable.");
        PeopleRequestContext.Actor actor = actorSupplier.get();
        HcmPepContext.Evidence evidence = evidenceSupplier.get();
        if (actor.tenantId() == null || actor.userId() == null || evidence == null
                || evidence.revalidateAt() == null
                || !evidence.revalidateAt().isAfter(OffsetDateTime.now(clock))
                || evidence.rolloutState() == null
                || !evidence.rolloutState().matches("[01]1[01]")
                || evidence.authority() == null
                || !WorkforceSnapshotContract.PERFORMANCE_PREVIEW_ROUTE.equals(
                evidence.authority().routeContractKey())
                || !WorkforceSnapshotContract.PERFORMANCE_PREVIEW_CAPABILITY.equals(
                evidence.authority().capabilityContractKey())
                || !"ACTION".equals(evidence.authority().routeKind())
                || !evidence.authority().predicatePolicyKeys().contains(
                "predicate.hcm-domain-target-population.v1")
                || !evidence.authority().targetBindingKinds().contains("TARGET_POPULATION")
                || evidence.contextKey() == null
                || !evidence.contextKey().matches("psc-[0-9a-f]{64}")
                || evidence.scopeKey() == null
                || !evidence.scopeKey().matches("scope-[0-9a-f]{32}")
                || evidence.decisionRevision() == null
                || !evidence.decisionRevision().matches("psr-[0-9a-f]{64}")
                || !actor.hasPermission("DATA.HR_TALENT", "UPDATE", "MANAGE")) {
            throw forbidden("Exact PER preview authority is required for the HRM snapshot.");
        }
        HcmPopulationScopeService.ResolvedPopulation population =
                populations.requireOperationsForMutation("UPDATE");
        populations.requireTrustedScope(
                population, "hcm.operations", "TARGET_POPULATION",
                "TALENT_TARGET_POPULATION");
        long tenantRevision = revision(population.targetPopulationRevision());
        long policyRevision = revision(
                population.scope().policyFingerprint() + ':' + evidence.contextKey());
        UUID correlation = UUID.nameUUIDFromBytes((
                evidence.contextKey() + ':' + evidence.decisionRevision())
                .getBytes(StandardCharsets.UTF_8));
        if (query.cursorToken() == null) {
            return new VerifiedWorkforceSnapshotRequest(
                    actor.tenantId(), tenantRevision, policyRevision,
                    VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                    correlation, query.purposeCode(), query.projection(), query.asOf(),
                    query.limit(), null, null, null);
        }
        VerifiedWorkforceSnapshotCursor cursor = cursors.verify(query.cursorToken());
        if (cursor.tenantId() != actor.tenantId()
                || !VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER.equals(
                cursor.callerModule())
                || !query.purposeCode().equals(cursor.purposeCode())
                || query.projection() != cursor.projection()
                || !query.asOf().equals(cursor.asOf())) {
            throw forbidden("The workforce snapshot cursor is outside the current authority.");
        }
        return new VerifiedWorkforceSnapshotRequest(
                actor.tenantId(), tenantRevision, policyRevision,
                VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                correlation, query.purposeCode(), query.projection(), query.asOf(),
                query.limit(), cursor.ownerRevision(), cursor.position(),
                query.cursorTokenDigest());
    }

    @Override
    public VerifiedWorkforceSnapshotCursor verifyIssuedCursor(
            VerifiedWorkforceSnapshotRequest request,
            WorkforceSnapshotPage page) {
        if (page.nextCursor() == null) {
            throw new IllegalArgumentException("An issued cursor is required for verification.");
        }
        return cursors.verify(page.nextCursor());
    }

    private long revision(String value) {
        if (value == null || value.isBlank()) throw unavailable(
                "The HRM population revision is unavailable.");
        String digest = HrisWorkforceSnapshotRevision.digest(value);
        return Long.parseLong(digest.substring(0, 15), 16) + 1;
    }

    private BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }
}
