package com.dwp.services.people.hris.contracts.tim003;

import com.dwp.contracts.hris.xcon.v1.Xcon027EmploymentAbsenceSnapshotV1;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Closed SVC-PEP-HRM-007 orchestration after workload authentication. */
@Service
final class TimeAbsenceOwnerService {

    private static final String ZERO_DIGEST = "0".repeat(64);

    private final TimeAbsenceOwnerPorts.OwnerGrantVerifierAuthority grants;
    private final TimeAbsenceOwnerPorts.ReplayReservation replay;
    private final TimeAbsenceOwnerPorts.OwnerFactProvider facts;
    private final TimeAbsenceOwnerPorts.ResponseSigner signer;
    private final TimeAbsenceOwnerPorts.Audit audit;
    private final TimeAbsenceOwnerCanonicalJson canonical;
    private final Clock clock;

    TimeAbsenceOwnerService(
            TimeAbsenceOwnerPorts.OwnerGrantVerifierAuthority grants,
            TimeAbsenceOwnerPorts.ReplayReservation replay,
            TimeAbsenceOwnerPorts.OwnerFactProvider facts,
            TimeAbsenceOwnerPorts.ResponseSigner signer,
            TimeAbsenceOwnerPorts.Audit audit,
            TimeAbsenceOwnerCanonicalJson canonical) {
        this(grants, replay, facts, signer, audit, canonical, Clock.systemUTC());
    }

    TimeAbsenceOwnerService(
            TimeAbsenceOwnerPorts.OwnerGrantVerifierAuthority grants,
            TimeAbsenceOwnerPorts.ReplayReservation replay,
            TimeAbsenceOwnerPorts.OwnerFactProvider facts,
            TimeAbsenceOwnerPorts.ResponseSigner signer,
            TimeAbsenceOwnerPorts.Audit audit,
            TimeAbsenceOwnerCanonicalJson canonical,
            Clock clock) {
        this.grants = grants;
        this.replay = replay;
        this.facts = facts;
        this.signer = signer;
        this.audit = audit;
        this.canonical = canonical;
        this.clock = clock;
    }

    TimeAbsenceOwnerContract.Success read(
            TimeAbsenceOwnerContract.VerifiedRequest request) {
        try {
            if (!replay.reserve(request)) {
                throw TimeAbsenceOwnerContract.Failure.invalidProof();
            }
            TimeAbsenceOwnerContract.OwnerGrant grant = grants.current(request.tenantId())
                    .orElseThrow(TimeAbsenceOwnerContract.Failure::forbiddenScope);
            if (!grant.exactlyMatches(request)) {
                throw TimeAbsenceOwnerContract.Failure.authorityStale();
            }
            TimeAbsenceOwnerContract.OwnerFacts ownerFacts = facts.readCurrent(request, grant)
                    .orElseThrow(TimeAbsenceOwnerContract.Failure::notFound);
            if (!ownerFacts.relationshipPublicId().equals(
                    request.request().relationshipPublicId())) {
                throw TimeAbsenceOwnerContract.Failure.contractInvalid();
            }
            if (ownerFacts.relationshipRevision()
                    < request.request().minimumRelationshipRevision()) {
                throw TimeAbsenceOwnerContract.Failure.revisionBelowMinimum();
            }

            Xcon027EmploymentAbsenceSnapshotV1 snapshot = snapshot(request, ownerFacts);
            TimeAbsenceOwnerContract.WorkloadResponseEvidence evidence = signer
                    .signSuccess(request, snapshot)
                    .filter(candidate -> evidenceMatches(request, snapshot, candidate))
                    .orElseThrow(TimeAbsenceOwnerContract.Failure::unavailable);
            if (!appendAudit(request, "SUCCESS", snapshot.contentSha256())) {
                throw TimeAbsenceOwnerContract.Failure.unavailable();
            }
            return new TimeAbsenceOwnerContract.Success(snapshot, evidence);
        } catch (TimeAbsenceOwnerContract.Failure failure) {
            if (!appendAudit(request, "DENIED_" + failure.code(),
                    denialDigest(request, failure))) {
                throw TimeAbsenceOwnerContract.Failure.unavailable();
            }
            throw failure;
        } catch (IllegalArgumentException exception) {
            TimeAbsenceOwnerContract.Failure failure =
                    TimeAbsenceOwnerContract.Failure.contractInvalid();
            if (!appendAudit(request, "DENIED_" + failure.code(),
                    denialDigest(request, failure))) {
                throw TimeAbsenceOwnerContract.Failure.unavailable();
            }
            throw failure;
        } catch (RuntimeException exception) {
            TimeAbsenceOwnerContract.Failure failure =
                    TimeAbsenceOwnerContract.Failure.unavailable();
            if (!appendAudit(request, "DENIED_" + failure.code(),
                    denialDigest(request, failure))) {
                throw TimeAbsenceOwnerContract.Failure.unavailable();
            }
            throw failure;
        }
    }

    Optional<TimeAbsenceOwnerContract.Problem> signedProblem(
            TimeAbsenceOwnerContract.VerifiedRequest request,
            TimeAbsenceOwnerContract.Failure failure) {
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        try {
            return signer.signProblem(request, failure, issuedAt)
                    .filter(problem -> problem.status() == failure.status()
                            && problem.code().equals(failure.code())
                            && problem.retryable() == failure.retryable()
                            && problem.correlationId().equals(request.correlationId())
                            && problem.issuedAt().equals(issuedAt)
                            && problem.errorDigest().equals(canonical.digest(
                                    problemDigestFields(problem)))
                            && canonical.validDigestJws(
                                    problem.signature(), "dwp-workload-error+jws",
                                    problem.keyId(), problem.errorDigest()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Xcon027EmploymentAbsenceSnapshotV1 snapshot(
            TimeAbsenceOwnerContract.VerifiedRequest request,
            TimeAbsenceOwnerContract.OwnerFacts facts) {
        String scopeDigest = canonical.digest(scopeFields(request));
        Instant validUntil = facts.asOf().plusSeconds(300);
        List<Xcon027EmploymentAbsenceSnapshotV1.EmploymentAbsenceAssignment> assignments =
                facts.assignments().stream().map(value ->
                        new Xcon027EmploymentAbsenceSnapshotV1.EmploymentAbsenceAssignment(
                                value.assignmentPublicId(), value.assignmentRevision(),
                                value.assignmentStatus(), value.legalEntityPublicId(),
                                value.organizationPublicId(), value.locationPublicId(),
                                value.timeZone(), value.effectiveFrom(), value.effectiveTo()))
                        .toList();
        Xcon027EmploymentAbsenceSnapshotV1 unsigned = new Xcon027EmploymentAbsenceSnapshotV1(
                facts.relationshipPublicId(), facts.relationshipRevision(),
                facts.workerPublicId(), facts.workerStatus(), facts.relationshipType(),
                facts.relationshipStatus(), facts.legalEntityPublicId(),
                facts.relationshipEffectiveFrom(), facts.relationshipEffectiveTo(),
                request.request().effectiveOn(), assignments, facts.relationshipRevision(),
                facts.asOf(), validUntil, TimeAbsenceOwnerContract.OWNER_CONTRACT_ID,
                TimeAbsenceOwnerContract.OWNER_SERVICE, TimeAbsenceOwnerContract.PURPOSE,
                scopeDigest, ZERO_DIGEST);
        String contentSha256 = canonical.digest(contentFields(unsigned));
        return new Xcon027EmploymentAbsenceSnapshotV1(
                unsigned.relationshipPublicId(), unsigned.relationshipRevision(),
                unsigned.workerPublicId(), unsigned.workerStatus(), unsigned.relationshipType(),
                unsigned.relationshipStatus(), unsigned.legalEntityPublicId(),
                unsigned.relationshipEffectiveFrom(), unsigned.relationshipEffectiveTo(),
                unsigned.effectiveOn(), unsigned.assignments(), unsigned.ownerRevision(),
                unsigned.asOf(), unsigned.validUntil(), unsigned.ownerContractId(),
                unsigned.ownerService(), unsigned.purposeCode(), unsigned.scopeDigest(),
                contentSha256);
    }

    private Map<String, Object> scopeFields(
            TimeAbsenceOwnerContract.VerifiedRequest request) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("tenantId", request.tenantId());
        value.put("callerSpiffeId", request.callerSpiffeId());
        value.put("audience", request.audience());
        value.put("tenantGrantRevision", request.tenantGrantRevision());
        value.put("businessTimeZone", request.businessTimeZone());
        value.put("relationshipPublicId", request.request().relationshipPublicId());
        value.put("minimumRelationshipRevision",
                request.request().minimumRelationshipRevision());
        value.put("effectiveOn", request.request().effectiveOn());
        value.put("purposeCode", request.purposeCode());
        value.put("projectionId", request.projectionId());
        value.put("authorizationRevision", request.authorizationRevision());
        value.put("fieldPolicyRevision", request.fieldPolicyRevision());
        return value;
    }

    private Map<String, Object> contentFields(
            Xcon027EmploymentAbsenceSnapshotV1 value) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("relationshipPublicId", value.relationshipPublicId());
        fields.put("relationshipRevision", value.relationshipRevision());
        fields.put("workerPublicId", value.workerPublicId());
        fields.put("workerStatus", value.workerStatus());
        fields.put("relationshipType", value.relationshipType());
        fields.put("relationshipStatus", value.relationshipStatus());
        fields.put("legalEntityPublicId", value.legalEntityPublicId());
        fields.put("relationshipEffectiveFrom", value.relationshipEffectiveFrom());
        fields.put("relationshipEffectiveTo", value.relationshipEffectiveTo());
        fields.put("effectiveOn", value.effectiveOn());
        fields.put("assignments", new ArrayList<>(value.assignments()));
        fields.put("ownerRevision", value.ownerRevision());
        fields.put("asOf", value.asOf());
        fields.put("validUntil", value.validUntil());
        fields.put("ownerContractId", value.ownerContractId());
        fields.put("ownerService", value.ownerService());
        fields.put("purposeCode", value.purposeCode());
        fields.put("scopeDigest", value.scopeDigest());
        return fields;
    }

    private boolean evidenceMatches(
            TimeAbsenceOwnerContract.VerifiedRequest request,
            Xcon027EmploymentAbsenceSnapshotV1 snapshot,
            TimeAbsenceOwnerContract.WorkloadResponseEvidence evidence) {
        return evidence.tenantId() == request.tenantId()
                && evidence.callerSpiffeId().equals(request.callerSpiffeId())
                && evidence.audience().equals(request.audience())
                && evidence.tenantGrantRevision() == request.tenantGrantRevision()
                && evidence.businessTimeZone().equals(request.businessTimeZone())
                && evidence.purposeCode().equals(request.purposeCode())
                && evidence.projectionId().equals(request.projectionId())
                && evidence.authorizationRevision() == request.authorizationRevision()
                && evidence.fieldPolicyRevision() == request.fieldPolicyRevision()
                && evidence.correlationId().equals(request.correlationId())
                && evidence.issuedAt().equals(snapshot.asOf())
                && evidence.asOf().equals(snapshot.asOf())
                && evidence.validUntil().equals(snapshot.validUntil())
                && evidence.scopeDigest().equals(snapshot.scopeDigest())
                && evidence.contentSha256().equals(snapshot.contentSha256())
                && evidence.evidenceDigest().equals(canonical.digest(
                        evidenceDigestFields(evidence)))
                && canonical.validDigestJws(
                        evidence.signature(), "dwp-workload-response+jws",
                        evidence.keyId(), evidence.evidenceDigest());
    }

    private Map<String, Object> evidenceDigestFields(
            TimeAbsenceOwnerContract.WorkloadResponseEvidence evidence) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tenantId", evidence.tenantId());
        fields.put("callerSpiffeId", evidence.callerSpiffeId());
        fields.put("audience", evidence.audience());
        fields.put("tenantGrantRevision", evidence.tenantGrantRevision());
        fields.put("businessTimeZone", evidence.businessTimeZone());
        fields.put("purposeCode", evidence.purposeCode());
        fields.put("projectionId", evidence.projectionId());
        fields.put("authorizationRevision", evidence.authorizationRevision());
        fields.put("fieldPolicyRevision", evidence.fieldPolicyRevision());
        fields.put("correlationId", evidence.correlationId());
        fields.put("issuedAt", evidence.issuedAt());
        fields.put("asOf", evidence.asOf());
        fields.put("validUntil", evidence.validUntil());
        fields.put("scopeDigest", evidence.scopeDigest());
        fields.put("contentSha256", evidence.contentSha256());
        fields.put("keyId", evidence.keyId());
        return fields;
    }

    private Map<String, Object> problemDigestFields(
            TimeAbsenceOwnerContract.Problem problem) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", problem.status());
        fields.put("code", problem.code());
        fields.put("retryable", problem.retryable());
        fields.put("correlationId", problem.correlationId());
        fields.put("issuedAt", problem.issuedAt());
        fields.put("keyId", problem.keyId());
        return fields;
    }

    private boolean appendAudit(
            TimeAbsenceOwnerContract.VerifiedRequest request,
            String outcome,
            String contentDigest) {
        try {
            return audit.appendAuthenticatedOutcome(request, outcome, contentDigest);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String denialDigest(
            TimeAbsenceOwnerContract.VerifiedRequest request,
            TimeAbsenceOwnerContract.Failure failure) {
        return canonical.digest(Map.of(
                "bindingId", TimeAbsenceOwnerContract.BINDING_ID,
                "code", failure.code(),
                "correlationId", request.correlationId(),
                "status", failure.status()));
    }
}
