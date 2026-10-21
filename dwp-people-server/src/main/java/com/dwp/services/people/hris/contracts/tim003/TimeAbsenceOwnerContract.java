package com.dwp.services.people.hris.contracts.tim003;

import com.dwp.contracts.hris.xcon.v1.Xcon027EmploymentAbsenceSnapshotV1;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesProvider;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Closed SVC-PEP-HRM-007 request, authority, data, evidence and problem types. */
public final class TimeAbsenceOwnerContract {

    public static final String BINDING_ID = "SVC-PEP-HRM-007";
    public static final String PATH_PREFIX =
            "/internal/hris-people/v1/employment-absence-snapshots/";
    public static final String ROUTE_TEMPLATE = PATH_PREFIX + "{relationshipPublicId}";
    public static final String PURPOSE = "TIM_LEAVE_EMPLOYMENT_RECOVERY";
    public static final String PROJECTION = "TIME_ABSENCE_V1";
    public static final String CALLER_SPIFFE =
            "spiffe://dwp/service/dwp-time-server";
    public static final String AUDIENCE = "dwp-people-server";
    public static final String OWNER_CONTRACT_ID =
            "SVC-PEP-HRM-007/TIME_ABSENCE_V1";
    public static final String OWNER_SERVICE = "dwp-people-server";

    private static final Pattern CODE = Pattern.compile(
            "^[A-Z0-9][A-Z0-9._-]{0,79}$");
    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> WORKER_STATUSES = Set.of(
            "PENDING", "ACTIVE", "LEAVE", "TERMINATED");
    private static final Set<String> RELATIONSHIP_STATUSES = Set.of(
            "PENDING", "ACTIVE", "INACTIVE", "TERMINATED");
    private static final Set<String> ASSIGNMENT_STATUSES = Set.of(
            "PENDING", "ACTIVE", "SUSPENDED", "ENDED");

    private TimeAbsenceOwnerContract() {
    }

    public enum IngressDenialReason {
        DEFAULT_OFF,
        METHOD_NOT_ALLOWED,
        PATH_INVALID,
        QUERY_INVALID,
        HEADER_OR_BODY_REJECTED,
        PROOF_REJECTED
    }

    /**
     * Sanitized pre-authentication evidence with no tenant, token or raw route input.
     * presentedCorrelationId is an unverified presentation and never authority.
     */
    public record IngressDenialEvidence(
            String bindingId,
            String routeTemplate,
            IngressDenialReason reason,
            int status,
            UUID presentedCorrelationId) {
        public IngressDenialEvidence {
            exact(bindingId, BINDING_ID, "bindingId");
            exact(routeTemplate, ROUTE_TEMPLATE, "routeTemplate");
            Objects.requireNonNull(reason, "reason");
            if (status < 400 || status > 599) {
                throw new IllegalArgumentException("status is invalid");
            }
        }

        static IngressDenialEvidence of(
                IngressDenialReason reason,
                int status,
                UUID presentedCorrelationId) {
            return new IngressDenialEvidence(
                    BINDING_ID, ROUTE_TEMPLATE, reason, status, presentedCorrelationId);
        }
    }

    public record Request(
            UUID relationshipPublicId,
            long minimumRelationshipRevision,
            LocalDate effectiveOn) {
        public Request {
            Objects.requireNonNull(relationshipPublicId, "relationshipPublicId");
            nonnegative(minimumRelationshipRevision, "minimumRelationshipRevision");
            Objects.requireNonNull(effectiveOn, "effectiveOn");
        }
    }

    /** Result of cryptographic workload verification; never constructed from browser headers. */
    public record VerifiedRequest(
            String method,
            String path,
            String queryFingerprint,
            String bindingId,
            UUID jti,
            UUID nonce,
            UUID correlationId,
            Instant issuedAt,
            Instant expiresAt,
            long tenantId,
            String callerSpiffeId,
            String audience,
            String requestSigningKeyId,
            long tenantGrantRevision,
            String businessTimeZone,
            String purposeCode,
            String projectionId,
            long authorizationRevision,
            long fieldPolicyRevision,
            Request request) {
        public VerifiedRequest {
            exact(method, "GET", "method");
            nonblank(path, 240, "path");
            digest(queryFingerprint, "queryFingerprint");
            exact(bindingId, BINDING_ID, "bindingId");
            Objects.requireNonNull(jti, "jti");
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(correlationId, "correlationId");
            if (jti.equals(nonce)) {
                throw new IllegalArgumentException("jti and nonce must differ");
            }
            if (!jti.equals(correlationId)) {
                throw new IllegalArgumentException("correlationId must equal jti");
            }
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(issuedAt)
                    || expiresAt.isAfter(issuedAt.plusSeconds(30))) {
                throw new IllegalArgumentException("workload proof lifetime is invalid");
            }
            positive(tenantId, "tenantId");
            exact(callerSpiffeId, CALLER_SPIFFE, "callerSpiffeId");
            exact(audience, AUDIENCE, "audience");
            nonblank(requestSigningKeyId, 160, "requestSigningKeyId");
            positive(tenantGrantRevision, "tenantGrantRevision");
            canonicalZone(businessTimeZone, "businessTimeZone");
            exact(purposeCode, PURPOSE, "purposeCode");
            exact(projectionId, PROJECTION, "projectionId");
            positive(authorizationRevision, "authorizationRevision");
            positive(fieldPolicyRevision, "fieldPolicyRevision");
            Objects.requireNonNull(request, "request");
            if (!path.equals(PATH_PREFIX + request.relationshipPublicId())) {
                throw new IllegalArgumentException("path and relationshipPublicId differ");
            }
            LocalDate signedDate = LocalDate.ofInstant(issuedAt, ZoneId.of(businessTimeZone));
            if (!signedDate.equals(request.effectiveOn())) {
                throw new IllegalArgumentException("effectiveOn must equal the signed tenant date");
            }
        }
    }

    public enum GrantLifecycle { ACTIVE, REVOKED }

    /** Independently loaded current owner authority; it is never derived from the JWT. */
    public record OwnerGrant(
            long tenantId,
            GrantLifecycle lifecycleState,
            String callerSpiffeId,
            String requestSigningKeyId,
            String purposeCode,
            String projectionId,
            String businessTimeZone,
            long tenantGrantRevision,
            long authorizationRevision,
            long fieldPolicyRevision) {
        public OwnerGrant {
            positive(tenantId, "tenantId");
            Objects.requireNonNull(lifecycleState, "lifecycleState");
            exact(callerSpiffeId, CALLER_SPIFFE, "callerSpiffeId");
            nonblank(requestSigningKeyId, 160, "requestSigningKeyId");
            exact(purposeCode, PURPOSE, "purposeCode");
            exact(projectionId, PROJECTION, "projectionId");
            canonicalZone(businessTimeZone, "businessTimeZone");
            positive(tenantGrantRevision, "tenantGrantRevision");
            positive(authorizationRevision, "authorizationRevision");
            positive(fieldPolicyRevision, "fieldPolicyRevision");
        }

        public boolean exactlyMatches(VerifiedRequest verified) {
            return lifecycleState == GrantLifecycle.ACTIVE
                    && tenantId == verified.tenantId()
                    && callerSpiffeId.equals(verified.callerSpiffeId())
                    && requestSigningKeyId.equals(verified.requestSigningKeyId())
                    && purposeCode.equals(verified.purposeCode())
                    && projectionId.equals(verified.projectionId())
                    && businessTimeZone.equals(verified.businessTimeZone())
                    && tenantGrantRevision == verified.tenantGrantRevision()
                    && authorizationRevision == verified.authorizationRevision()
                    && fieldPolicyRevision == verified.fieldPolicyRevision();
        }
    }

    public record AssignmentFact(
            UUID assignmentPublicId,
            long assignmentRevision,
            String assignmentStatus,
            UUID legalEntityPublicId,
            UUID organizationPublicId,
            UUID locationPublicId,
            String timeZone,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        public AssignmentFact {
            Objects.requireNonNull(assignmentPublicId, "assignmentPublicId");
            nonnegative(assignmentRevision, "assignmentRevision");
            member(assignmentStatus, ASSIGNMENT_STATUSES, "assignmentStatus");
            Objects.requireNonNull(legalEntityPublicId, "legalEntityPublicId");
            if ((locationPublicId == null) != (timeZone == null)) {
                throw new IllegalArgumentException(
                        "timeZone must be null exactly when locationPublicId is null");
            }
            if (timeZone != null) canonicalZone(timeZone, "timeZone");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            exclusiveRange(effectiveFrom, effectiveTo, "assignment");
        }
    }

    /** One repeatable-read People owner snapshot before canonical digests are attached. */
    public record OwnerFacts(
            UUID relationshipPublicId,
            long relationshipRevision,
            UUID workerPublicId,
            String workerStatus,
            String relationshipType,
            String relationshipStatus,
            UUID legalEntityPublicId,
            LocalDate relationshipEffectiveFrom,
            LocalDate relationshipEffectiveTo,
            List<AssignmentFact> assignments,
            Instant asOf) {
        public OwnerFacts {
            Objects.requireNonNull(relationshipPublicId, "relationshipPublicId");
            nonnegative(relationshipRevision, "relationshipRevision");
            Objects.requireNonNull(workerPublicId, "workerPublicId");
            member(workerStatus, WORKER_STATUSES, "workerStatus");
            code(relationshipType, "relationshipType");
            member(relationshipStatus, RELATIONSHIP_STATUSES, "relationshipStatus");
            Objects.requireNonNull(legalEntityPublicId, "legalEntityPublicId");
            Objects.requireNonNull(relationshipEffectiveFrom,
                    "relationshipEffectiveFrom");
            exclusiveRange(relationshipEffectiveFrom, relationshipEffectiveTo,
                    "relationship");
            assignments = List.copyOf(Objects.requireNonNull(assignments, "assignments"));
            if (assignments.size() > 1000) {
                throw new IllegalArgumentException("assignments exceeds 1000 items");
            }
            Set<UUID> ids = new HashSet<>();
            String previous = null;
            for (AssignmentFact assignment : assignments) {
                Objects.requireNonNull(assignment, "assignment");
                if (!legalEntityPublicId.equals(assignment.legalEntityPublicId())) {
                    throw new IllegalArgumentException("assignment legal entity differs");
                }
                String current = assignment.assignmentPublicId().toString();
                if (!ids.add(assignment.assignmentPublicId())) {
                    throw new IllegalArgumentException("duplicate assignmentPublicId");
                }
                if (previous != null && previous.compareTo(current) >= 0) {
                    throw new IllegalArgumentException("assignments are not canonical");
                }
                previous = current;
            }
            Objects.requireNonNull(asOf, "asOf");
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WorkloadResponseEvidence(
            long tenantId,
            String callerSpiffeId,
            String audience,
            long tenantGrantRevision,
            String businessTimeZone,
            String purposeCode,
            String projectionId,
            long authorizationRevision,
            long fieldPolicyRevision,
            UUID correlationId,
            Instant issuedAt,
            Instant asOf,
            Instant validUntil,
            String scopeDigest,
            String contentSha256,
            String evidenceDigest,
            String keyId,
            String signature) {
        public WorkloadResponseEvidence {
            positive(tenantId, "tenantId");
            exact(callerSpiffeId, CALLER_SPIFFE, "callerSpiffeId");
            exact(audience, AUDIENCE, "audience");
            positive(tenantGrantRevision, "tenantGrantRevision");
            canonicalZone(businessTimeZone, "businessTimeZone");
            exact(purposeCode, PURPOSE, "purposeCode");
            exact(projectionId, PROJECTION, "projectionId");
            positive(authorizationRevision, "authorizationRevision");
            positive(fieldPolicyRevision, "fieldPolicyRevision");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(validUntil, "validUntil");
            if (!issuedAt.equals(asOf)) {
                throw new IllegalArgumentException("issuedAt must equal asOf");
            }
            digest(scopeDigest, "scopeDigest");
            digest(contentSha256, "contentSha256");
            digest(evidenceDigest, "evidenceDigest");
            nonblank(keyId, 160, "keyId");
            compactJws(signature, "signature");
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Success(
            Xcon027EmploymentAbsenceSnapshotV1 data,
            WorkloadResponseEvidence evidence) {
        public Success {
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(evidence, "evidence");
            if (!data.asOf().equals(evidence.asOf())
                    || !data.validUntil().equals(evidence.validUntil())
                    || !data.scopeDigest().equals(evidence.scopeDigest())
                    || !data.contentSha256().equals(evidence.contentSha256())) {
                throw new IllegalArgumentException("data and evidence bindings differ");
            }
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Problem(
            int status,
            String code,
            boolean retryable,
            UUID correlationId,
            Instant issuedAt,
            String errorDigest,
            String keyId,
            String signature) {
        public Problem {
            if (status < 400 || status > 599) {
                throw new IllegalArgumentException("status is invalid");
            }
            TimeAbsenceOwnerContract.code(code, "code");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(issuedAt, "issuedAt");
            digest(errorDigest, "errorDigest");
            nonblank(keyId, 160, "keyId");
            compactJws(signature, "signature");
        }
    }

    public static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int status;
        private final String code;
        private final boolean retryable;

        private Failure(int status, String code, boolean retryable) {
            super(code);
            this.status = status;
            this.code = code;
            this.retryable = retryable;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }

        public static Failure dateRolled() {
            return new Failure(409, "DATE_ROLLED", true);
        }

        public static Failure authorityStale() {
            return new Failure(409, "OWNER_AUTHORITY_STALE", true);
        }

        public static Failure revisionBelowMinimum() {
            return new Failure(409, "OWNER_REVISION_BELOW_MINIMUM", true);
        }

        public static Failure invalidProof() {
            return new Failure(401, "INVALID_WORKLOAD_PROOF", false);
        }

        public static Failure forbiddenScope() {
            return new Failure(403, "FORBIDDEN_OWNER_SCOPE", false);
        }

        public static Failure notFound() {
            return new Failure(404, "OWNER_RELATIONSHIP_NOT_FOUND", false);
        }

        public static Failure configurationInvalid() {
            return new Failure(422, "OWNER_CONFIGURATION_INVALID", false);
        }

        public static Failure contractInvalid() {
            return new Failure(422, "OWNER_CONTRACT_INVALID", false);
        }

        public static Failure unavailable() {
            return new Failure(503, "OWNER_TEMPORARILY_UNAVAILABLE", true);
        }

        public static Failure rateLimited() {
            return new Failure(429, "OWNER_RATE_LIMITED", true);
        }

        public static Failure invalidRequestBinding() {
            return new Failure(400, "INVALID_REQUEST_BINDING", false);
        }
    }

    static void positive(long value, String field) {
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
    }

    static void nonnegative(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must be nonnegative");
    }

    static void exact(String value, String expected, String field) {
        if (!expected.equals(value)) {
            throw new IllegalArgumentException(field + " must equal " + expected);
        }
    }

    static void nonblank(String value, int maximum, String field) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.length() > maximum || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    static void code(String value, String field) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not canonical");
        }
    }

    static void digest(String value, String field) {
        if (value == null || !DIGEST.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a lowercase SHA-256");
        }
    }

    static void member(String value, Set<String> allowed, String field) {
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(field + " is not a closed value");
        }
    }

    static void canonicalZone(String value, String field) {
        nonblank(value, 80, field);
        if (!"UTC".equals(value)
                && !ZoneRulesProvider.getAvailableZoneIds().contains(value)) {
            throw new IllegalArgumentException(field + " is not a canonical zone");
        }
        ZoneId zone = ZoneId.of(value);
        if (!value.equals(zone.getId())
                || (!"UTC".equals(value) && zone.getRules().isFixedOffset())) {
            throw new IllegalArgumentException(field + " is a fixed offset or alias");
        }
    }

    static void exclusiveRange(LocalDate from, LocalDate to, String field) {
        if (to != null && !to.isAfter(from)) {
            throw new IllegalArgumentException(field + " effectiveTo must be exclusive");
        }
    }

    static void compactJws(String value, String field) {
        nonblank(value, 4096, field);
        String[] segments = value.split("\\.", -1);
        if (segments.length != 3 || segments[0].isEmpty()
                || segments[1].isEmpty() || segments[2].isEmpty()) {
            throw new IllegalArgumentException(field + " is not a compact JWS");
        }
    }
}
