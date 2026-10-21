package com.dwp.services.time.absence.employment;

import com.dwp.contracts.hris.xcon.v1.Xcon027EmploymentAbsenceSnapshotV1;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.OwnerDenied;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.OwnerFailure;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.OwnerGrant;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.OwnerProblem;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.OwnerRecovered;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.OwnerRequestProof;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.OwnerResult;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.OwnerSuccess;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.ResponseSigningKey;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.TenantContext;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.WireFailure;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.WireProblem;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.WireSuccess;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Fail-closed client for SVC-PEP-HRM-007; it returns zero facts on every doubt. */
public final class EmploymentAbsenceOwnerClient {

    private static final Set<String> CONTEXT_REFRESH_CODES =
            Set.of("DATE_ROLLED", "OWNER_AUTHORITY_STALE");
    private static final Set<String> RETRYABLE_CODES = Set.of(
            "OWNER_REVISION_BELOW_MINIMUM", "OWNER_RATE_LIMITED",
            "OWNER_TEMPORARILY_UNAVAILABLE");
    private static final Map<String, Integer> PERMANENT_CODES = Map.of(
            "INVALID_REQUEST_BINDING", 400,
            "INVALID_WORKLOAD_PROOF", 401,
            "FORBIDDEN_OWNER_SCOPE", 403,
            "OWNER_RELATIONSHIP_NOT_FOUND", 404,
            "OWNER_CONFIGURATION_INVALID", 422,
            "OWNER_CONTRACT_INVALID", 422);

    private final EmploymentAbsenceContract.OwnerGrantAuthority grants;
    private final EmploymentAbsenceContract.TenantContextAuthority contexts;
    private final EmploymentAbsenceContract.OwnerProofIssuer issuer;
    private final EmploymentAbsenceContract.OwnerTransport transport;
    private final EmploymentAbsenceContract.ResponseKeyAuthority keys;
    private final EmploymentAbsenceContract.OwnerAudit audit;
    private final EmploymentAbsenceCanonicalJson canonical;
    private final EmploymentAbsenceJwsVerifier jws;
    private final Clock clock;

    public EmploymentAbsenceOwnerClient(
            EmploymentAbsenceContract.OwnerGrantAuthority grants,
            EmploymentAbsenceContract.TenantContextAuthority contexts,
            EmploymentAbsenceContract.OwnerProofIssuer issuer,
            EmploymentAbsenceContract.OwnerTransport transport,
            EmploymentAbsenceContract.ResponseKeyAuthority keys,
            EmploymentAbsenceContract.OwnerAudit audit,
            ObjectMapper mapper,
            Clock clock) {
        this.grants = grants;
        this.contexts = contexts;
        this.issuer = issuer;
        this.transport = transport;
        this.keys = keys;
        this.audit = audit;
        this.canonical = new EmploymentAbsenceCanonicalJson(mapper);
        this.jws = new EmploymentAbsenceJwsVerifier(mapper);
        this.clock = clock;
    }

    public OwnerResult recover(
            long tenantId,
            UUID relationshipPublicId,
            long minimumRelationshipRevision,
            UUID correlationId) {
        OwnerRequestProof proof;
        OwnerGrant grant;
        TenantContext context;
        try {
            Instant now = clock.instant();
            context = contexts.current(tenantId)
                    .orElseThrow(Unavailable::new);
            grant = grants.current(tenantId)
                    .filter(OwnerGrant::active)
                    .filter(context::matches)
                    .orElseThrow(Unavailable::new);
            LocalDate currentDate = context.currentDate(now);
            proof = issuer.issue(
                            grant, relationshipPublicId,
                            minimumRelationshipRevision, currentDate, correlationId)
                    .filter(candidate -> proofMatches(
                            candidate, grant, context, relationshipPublicId,
                            minimumRelationshipRevision, currentDate, correlationId, now))
                    .orElseThrow(Unavailable::new);

        } catch (RuntimeException exception) {
            OwnerResult result = new OwnerDenied(
                    OwnerFailure.retryable("OWNER_AUTHORITY_UNAVAILABLE"));
            return audited(tenantId, relationshipPublicId, correlationId, result);
        }
        EmploymentAbsenceContract.OwnerWireResult wire;
        try {
            wire = transport.exchange(proof);
        } catch (RuntimeException transportFailure) {
            return audited(tenantId, relationshipPublicId, correlationId,
                    new OwnerDenied(OwnerFailure.retryable("UNTRUSTED_OWNER_ERROR")));
        }
        Instant verificationNow = clock.instant();
        OwnerResult result;
        if (wire instanceof WireSuccess success) {
            result = verifySuccess(
                    success.value(), proof, grant, context, verificationNow);
        } else if (wire instanceof WireProblem problem) {
            result = new OwnerDenied(classifyVerifiedProblem(
                    problem.value(), proof, verificationNow));
        } else {
            result = new OwnerDenied(OwnerFailure.retryable("UNTRUSTED_OWNER_ERROR"));
        }
        return audited(tenantId, relationshipPublicId, correlationId, result);
    }

    /** Independent current-authority check for the no-refetch covered-event path. */
    public boolean currentlyAuthorizes(EmploymentAbsenceProjection projection) {
        try {
            Instant now = clock.instant();
            TenantContext context = contexts.current(projection.tenantId()).orElseThrow();
            OwnerGrant grant = grants.current(projection.tenantId())
                    .filter(OwnerGrant::active).orElseThrow();
            if (!context.matches(grant)
                    || projection.ownerSnapshot() == null
                    || projection.ownerEvidence() == null
                    || !projection.coveredAndReadable(
                            context.currentDate(now), now, grant, context, false)) {
                return false;
            }
            canonical.validateGeneratedSnapshot(projection.ownerSnapshot());
            if (!projection.ownerSnapshot().contentSha256().equals(
                    canonical.contentDigest(projection.ownerSnapshot()))
                    || !projection.ownerSnapshot().scopeDigest().equals(
                    canonical.storedScopeDigest(projection, grant))) {
                return false;
            }
            return keys.current(projection.ownerEvidence().keyId())
                    .filter(ResponseSigningKey::active)
                    .filter(key -> key.keyId().equals(
                            projection.ownerEvidence().keyId()))
                    .isPresent();
        } catch (RuntimeException unavailableOrCorrupt) {
            return false;
        }
    }

    /** Revalidates time and current authority immediately before a fenced DB finalization. */
    public OwnerResult revalidateForFinalization(
            OwnerResult result,
            UUID relationshipPublicId,
            long minimumRelationshipRevision) {
        if (!(result instanceof OwnerRecovered recovered)) return result;
        try {
            Instant now = clock.instant();
            var snapshot = recovered.snapshot();
            var evidence = recovered.evidence();
            TenantContext context = contexts.current(evidence.tenantId()).orElseThrow();
            OwnerGrant grant = grants.current(evidence.tenantId())
                    .filter(OwnerGrant::active).orElseThrow();
            if (!context.matches(grant) || !grant.equals(recovered.grant())
                    || evidence.tenantGrantRevision() != grant.tenantGrantRevision()
                    || evidence.authorizationRevision() != grant.authorizationRevision()
                    || evidence.fieldPolicyRevision() != grant.fieldPolicyRevision()
                    || !evidence.businessTimeZone().equals(grant.businessTimeZone())
                    || !evidence.purposeCode().equals(grant.purposeCode())
                    || !evidence.projectionId().equals(grant.projectionId())) {
                return new OwnerDenied(OwnerFailure.refresh("OWNER_AUTHORITY_STALE"));
            }
            if (evidence.asOf().isAfter(now.plusSeconds(5))) {
                return new OwnerDenied(OwnerFailure.permanent("OWNER_RESPONSE_INVALID"));
            }
            if (!snapshot.effectiveOn().equals(context.currentDate(now))) {
                return new OwnerDenied(OwnerFailure.refresh("DATE_ROLLED"));
            }
            if (!snapshot.validUntil().isAfter(now)) {
                return new OwnerDenied(OwnerFailure.retryable("OWNER_RESPONSE_EXPIRED"));
            }
            if (!snapshot.relationshipPublicId().equals(relationshipPublicId)
                    || snapshot.relationshipRevision() < minimumRelationshipRevision
                    || !snapshot.contentSha256().equals(canonical.contentDigest(snapshot))
                    || keys.current(evidence.keyId())
                            .filter(ResponseSigningKey::active)
                            .filter(key -> key.keyId().equals(evidence.keyId()))
                            .isEmpty()) {
                return new OwnerDenied(OwnerFailure.permanent("OWNER_RESPONSE_INVALID"));
            }
            return new OwnerRecovered(snapshot, evidence, grant);
        } catch (RuntimeException unavailable) {
            return new OwnerDenied(
                    OwnerFailure.retryable("OWNER_AUTHORITY_UNAVAILABLE"));
        }
    }

    private OwnerResult verifySuccess(
            OwnerSuccess success,
            OwnerRequestProof proof,
            OwnerGrant firstGrant,
            TenantContext firstContext,
            Instant now) {
        try {
            requireNoCache(success.headers(), "application/json");
            canonical.validateGeneratedSnapshot(success.data());
        } catch (RuntimeException malformed) {
            return new OwnerDenied(OwnerFailure.permanent("OWNER_RESPONSE_INVALID"));
        }
        Xcon027EmploymentAbsenceSnapshotV1 snapshot = success.data();
        var evidence = success.evidence();
        try {
            if (!snapshot.relationshipPublicId().equals(proof.relationshipPublicId())
                    || snapshot.relationshipRevision() < proof.minimumRelationshipRevision()
                    || !snapshot.effectiveOn().equals(proof.effectiveOn())
                    || !snapshot.ownerContractId().equals(EmploymentAbsenceContract.OWNER_CONTRACT)
                    || !snapshot.ownerService().equals(EmploymentAbsenceContract.OWNER_SERVICE)
                    || !snapshot.purposeCode().equals(EmploymentAbsenceContract.PURPOSE)
                    || !snapshot.validUntil().isAfter(now)
                    || evidence.asOf().isAfter(now.plusSeconds(5))
                    || !evidence.correlationId().equals(proof.jti())
                    || evidence.tenantId() != proof.tenantId()
                    || !evidence.businessTimeZone().equals(proof.businessTimeZone())
                    || evidence.tenantGrantRevision() != proof.tenantGrantRevision()
                    || evidence.authorizationRevision() != proof.authorizationRevision()
                    || evidence.fieldPolicyRevision() != proof.fieldPolicyRevision()
                    || !evidence.purposeCode().equals(firstGrant.purposeCode())
                    || !evidence.projectionId().equals(firstGrant.projectionId())
                    || !snapshot.scopeDigest().equals(canonical.scopeDigest(proof))
                    || !snapshot.contentSha256().equals(canonical.contentDigest(snapshot))
                    || !evidence.evidenceDigest().equals(canonical.evidenceDigest(evidence))) {
                return new OwnerDenied(OwnerFailure.permanent("OWNER_RESPONSE_INVALID"));
            }
        } catch (RuntimeException malformed) {
            return new OwnerDenied(OwnerFailure.permanent("OWNER_RESPONSE_INVALID"));
        }
        ResponseSigningKey key;
        try {
            key = keys.current(evidence.keyId()).orElse(null);
        } catch (RuntimeException unavailable) {
            return new OwnerDenied(
                    OwnerFailure.retryable("OWNER_AUTHORITY_UNAVAILABLE"));
        }
        if (key == null || !jws.verify(evidence.signature(),
                "dwp-workload-response+jws", evidence.keyId(),
                evidence.evidenceDigest(), key)) {
            return new OwnerDenied(OwnerFailure.permanent("OWNER_SIGNATURE_INVALID"));
        }
        // Current authority is loaded independently again after remote I/O.
        Optional<OwnerGrant> currentGrant;
        Optional<TenantContext> currentContext;
        try {
            currentGrant = grants.current(proof.tenantId());
            currentContext = contexts.current(proof.tenantId());
        } catch (RuntimeException unavailable) {
            return new OwnerDenied(
                    OwnerFailure.retryable("OWNER_AUTHORITY_UNAVAILABLE"));
        }
        if (currentGrant.isEmpty() || currentContext.isEmpty()
                || !currentGrant.get().active()
                || !currentGrant.get().equals(firstGrant)
                || !currentContext.get().equals(firstContext)
                || !currentContext.get().matches(currentGrant.get())
                || !currentContext.get().currentDate(evidence.asOf())
                        .equals(proof.effectiveOn())) {
            return new OwnerDenied(OwnerFailure.refresh("OWNER_AUTHORITY_STALE"));
        }
        return new OwnerRecovered(snapshot, evidence, currentGrant.get());
    }

    private OwnerFailure classifyVerifiedProblem(
            OwnerProblem problem,
            OwnerRequestProof proof,
            Instant now) {
        try {
            requireNoCache(problem.headers(), "application/problem+json");
            if (!problem.correlationId().equals(proof.jti())
                    || problem.issuedAt().isAfter(now.plusSeconds(5))
                    || problem.issuedAt().isBefore(now.minusSeconds(35))
                    || !EmploymentAbsenceContract.closedProblemCodes().contains(problem.code())
                    || !problem.errorDigest().equals(canonical.problemDigest(problem))) {
                return OwnerFailure.retryable("UNTRUSTED_OWNER_ERROR");
            }
            ResponseSigningKey key = keys.current(problem.keyId()).orElse(null);
            if (key == null || !jws.verify(problem.signature(),
                    "dwp-workload-error+jws", problem.keyId(),
                    problem.errorDigest(), key)) {
                return OwnerFailure.retryable("UNTRUSTED_OWNER_ERROR");
            }
            if (CONTEXT_REFRESH_CODES.contains(problem.code())
                    && problem.httpStatus() == 409 && problem.retryable()) {
                return OwnerFailure.refresh(problem.code());
            }
            if (RETRYABLE_CODES.contains(problem.code()) && problem.retryable()
                    && switch (problem.code()) {
                        case "OWNER_REVISION_BELOW_MINIMUM" -> problem.httpStatus() == 409;
                        case "OWNER_RATE_LIMITED" -> problem.httpStatus() == 429;
                        default -> problem.httpStatus() == 503;
                    }) {
                return OwnerFailure.retryable(problem.code());
            }
            Integer expectedStatus = PERMANENT_CODES.get(problem.code());
            if (expectedStatus != null && expectedStatus == problem.httpStatus()
                    && !problem.retryable()) {
                return OwnerFailure.permanent(problem.code());
            }
            return OwnerFailure.retryable("UNTRUSTED_OWNER_ERROR");
        } catch (RuntimeException exception) {
            return OwnerFailure.retryable("UNTRUSTED_OWNER_ERROR");
        }
    }

    private static boolean proofMatches(
            OwnerRequestProof proof,
            OwnerGrant grant,
            TenantContext context,
            UUID relationshipPublicId,
            long minimumRevision,
            LocalDate effectiveOn,
            UUID correlationId,
            Instant now) {
        return proof.tenantId() == grant.tenantId()
                && proof.relationshipPublicId().equals(relationshipPublicId)
                && proof.minimumRelationshipRevision() == minimumRevision
                && proof.effectiveOn().equals(effectiveOn)
                && proof.businessTimeZone().equals(context.businessTimeZone())
                && proof.tenantGrantRevision() == grant.tenantGrantRevision()
                && proof.authorizationRevision() == grant.authorizationRevision()
                && proof.fieldPolicyRevision() == grant.fieldPolicyRevision()
                && proof.requestSigningKeyId().equals(grant.requestSigningKeyId())
                && proof.purpose().equals(grant.purposeCode())
                && proof.projection().equals(grant.projectionId())
                && proof.jti().equals(correlationId)
                && !proof.issuedAt().isAfter(now.plusSeconds(5))
                && proof.expiresAt().isAfter(now.minusSeconds(5));
    }

    private OwnerResult audited(
            long tenantId,
            UUID relationshipPublicId,
            UUID correlationId,
            OwnerResult result) {
        String outcome;
        String digest;
        if (result instanceof OwnerRecovered recovered) {
            outcome = "SUCCESS";
            digest = recovered.snapshot().contentSha256();
        } else {
            OwnerDenied denied = (OwnerDenied) result;
            outcome = "DENIED_" + denied.failure().code();
            digest = EmploymentAbsenceCanonicalJson.sha256(
                    tenantId + ":" + relationshipPublicId + ":"
                            + correlationId + ":" + outcome);
        }
        try {
            if (!audit.append(tenantId, relationshipPublicId, correlationId,
                    outcome, digest, auditEvidence(result))) {
                return new OwnerDenied(
                        OwnerFailure.retryable("OWNER_AUDIT_UNAVAILABLE"));
            }
            return result;
        } catch (RuntimeException exception) {
            return new OwnerDenied(
                    OwnerFailure.retryable("OWNER_AUDIT_UNAVAILABLE"));
        }
    }

    private static Map<String, Object> auditEvidence(OwnerResult result) {
        if (result instanceof OwnerRecovered recovered) {
            return Map.of(
                    "tenantGrantRevision", recovered.grant().tenantGrantRevision(),
                    "authorizationRevision", recovered.grant().authorizationRevision(),
                    "fieldPolicyRevision", recovered.grant().fieldPolicyRevision(),
                    "businessTimeZone", recovered.grant().businessTimeZone(),
                    "purposeCode", recovered.grant().purposeCode(),
                    "projectionId", recovered.grant().projectionId(),
                    "effectiveOn", recovered.snapshot().effectiveOn().toString(),
                    "keyId", recovered.evidence().keyId());
        }
        OwnerDenied denied = (OwnerDenied) result;
        return Map.of("denialCode", denied.failure().code(),
                "failureKind", denied.failure().kind().name());
    }

    private static void requireNoCache(Map<String, String> headers, String contentType) {
        if (headers.size() != 3
                || !contentType.equals(headers.get("content-type"))
                || !"no-store".equals(headers.get("cache-control"))
                || !"no-cache".equals(headers.get("pragma"))) {
            throw new IllegalArgumentException("owner response headers are not closed");
        }
    }

    private static final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
