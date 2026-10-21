package com.dwp.services.time.absence.employment;

import com.dwp.contracts.hris.xcon.v1.Xcon027EmploymentAbsenceSnapshotV1;

import java.security.PublicKey;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRulesProvider;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Closed Time-side types for the TIM-003 employment/absence boundary. */
public final class EmploymentAbsenceContract {

    public static final String CONSUMER =
            "dwp-time-server.tim003-employment-absence.v1";
    public static final String EVENT_TYPE =
            "dwp.hris.people.employment-changed.v1";
    public static final String EVENT_SOURCE =
            "urn:dwp:people:hris:employment";
    public static final String AGGREGATE_TYPE = "WORK_RELATIONSHIP";
    public static final String BINDING_ID = "SVC-PEP-HRM-007";
    public static final String PURPOSE = "TIM_LEAVE_EMPLOYMENT_RECOVERY";
    public static final String PROJECTION = "TIME_ABSENCE_V1";
    public static final String CALLER_SPIFFE =
            "spiffe://dwp/service/dwp-time-server";
    public static final String OWNER_SPIFFE =
            "spiffe://dwp/service/dwp-people-server";
    public static final String OWNER_SERVICE = "dwp-people-server";
    public static final String OWNER_CONTRACT =
            "SVC-PEP-HRM-007/TIME_ABSENCE_V1";
    public static final String OWNER_RESPONSE_KEY_PURPOSE =
            "SVC-PEP-HRM-007_RESPONSE";
    public static final String PATH_PREFIX =
            "/internal/hris-people/v1/employment-absence-snapshots/";

    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern CODE =
            Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,119}$");

    private EmploymentAbsenceContract() {
    }

    public enum FreshnessState {
        CURRENT, STALE, REFETCH_PENDING, GAP, QUARANTINED
    }

    public enum RecoveryOutcome {
        NOT_ATTEMPTED, PENDING, SUCCEEDED, RETRYABLE_FAILURE, PERMANENT_FAILURE
    }

    public enum GrantLifecycle {
        ACTIVE, REVOKED
    }

    public enum OwnerFailureKind {
        RETRYABLE,
        CONTEXT_REFRESH,
        PERMANENT
    }

    /** Independently materialized current authority; request/response bytes are never authority. */
    public record OwnerGrant(
            long tenantId,
            GrantLifecycle lifecycle,
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
            Objects.requireNonNull(lifecycle, "lifecycle");
            exact(callerSpiffeId, CALLER_SPIFFE, "callerSpiffeId");
            bounded(requestSigningKeyId, 160, "requestSigningKeyId");
            exact(purposeCode, PURPOSE, "purposeCode");
            exact(projectionId, PROJECTION, "projectionId");
            canonicalZone(businessTimeZone);
            positive(tenantGrantRevision, "tenantGrantRevision");
            positive(authorizationRevision, "authorizationRevision");
            positive(fieldPolicyRevision, "fieldPolicyRevision");
        }

        public boolean active() {
            return lifecycle == GrantLifecycle.ACTIVE;
        }
    }

    public record TenantContext(
            long tenantId,
            String businessTimeZone,
            long tenantGrantRevision,
            long authorizationRevision,
            long fieldPolicyRevision) {
        public TenantContext {
            positive(tenantId, "tenantId");
            canonicalZone(businessTimeZone);
            positive(tenantGrantRevision, "tenantGrantRevision");
            positive(authorizationRevision, "authorizationRevision");
            positive(fieldPolicyRevision, "fieldPolicyRevision");
        }

        public LocalDate currentDate(Instant instant) {
            return LocalDate.ofInstant(Objects.requireNonNull(instant, "instant"),
                    ZoneId.of(businessTimeZone));
        }

        public boolean matches(OwnerGrant grant) {
            return tenantId == grant.tenantId()
                    && businessTimeZone.equals(grant.businessTimeZone())
                    && tenantGrantRevision == grant.tenantGrantRevision()
                    && authorizationRevision == grant.authorizationRevision()
                    && fieldPolicyRevision == grant.fieldPolicyRevision();
        }
    }

    /** Fully bound request proof produced only by an admitted issuer implementation. */
    public record OwnerRequestProof(
            long tenantId,
            UUID relationshipPublicId,
            long minimumRelationshipRevision,
            LocalDate effectiveOn,
            String businessTimeZone,
            long tenantGrantRevision,
            long authorizationRevision,
            long fieldPolicyRevision,
            String requestSigningKeyId,
            UUID jti,
            UUID nonce,
            Instant issuedAt,
            Instant expiresAt,
            String method,
            String concretePath,
            String purpose,
            String projection,
            String compactJwt,
            String clientCertificateSha256) {
        public OwnerRequestProof {
            positive(tenantId, "tenantId");
            Objects.requireNonNull(relationshipPublicId, "relationshipPublicId");
            nonnegative(minimumRelationshipRevision, "minimumRelationshipRevision");
            Objects.requireNonNull(effectiveOn, "effectiveOn");
            canonicalZone(businessTimeZone);
            positive(tenantGrantRevision, "tenantGrantRevision");
            positive(authorizationRevision, "authorizationRevision");
            positive(fieldPolicyRevision, "fieldPolicyRevision");
            bounded(requestSigningKeyId, 160, "requestSigningKeyId");
            Objects.requireNonNull(jti, "jti");
            Objects.requireNonNull(nonce, "nonce");
            if (jti.equals(nonce)) throw new IllegalArgumentException("jti and nonce differ");
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(issuedAt) || expiresAt.isAfter(issuedAt.plusSeconds(30))) {
                throw new IllegalArgumentException("proof lifetime is invalid");
            }
            exact(method, "GET", "method");
            exact(concretePath, PATH_PREFIX + relationshipPublicId, "concretePath");
            exact(purpose, PURPOSE, "purpose");
            exact(projection, PROJECTION, "projection");
            compactJws(compactJwt, 16_384, "compactJwt");
            digest(clientCertificateSha256, "clientCertificateSha256");
            if (!effectiveOn.equals(LocalDate.ofInstant(issuedAt, ZoneId.of(businessTimeZone)))) {
                throw new IllegalArgumentException("effectiveOn differs from signed tenant date");
            }
        }
    }

    public record ResponseEvidence(
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
        public ResponseEvidence {
            positive(tenantId, "tenantId");
            exact(callerSpiffeId, CALLER_SPIFFE, "callerSpiffeId");
            exact(audience, OWNER_SERVICE, "audience");
            positive(tenantGrantRevision, "tenantGrantRevision");
            canonicalZone(businessTimeZone);
            exact(purposeCode, PURPOSE, "purposeCode");
            exact(projectionId, PROJECTION, "projectionId");
            positive(authorizationRevision, "authorizationRevision");
            positive(fieldPolicyRevision, "fieldPolicyRevision");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(validUntil, "validUntil");
            if (!issuedAt.equals(asOf)) throw new IllegalArgumentException("issuedAt differs");
            digest(scopeDigest, "scopeDigest");
            digest(contentSha256, "contentSha256");
            digest(evidenceDigest, "evidenceDigest");
            bounded(keyId, 160, "keyId");
            compactJws(signature, 4096, "signature");
        }
    }

    public record OwnerSuccess(
            Xcon027EmploymentAbsenceSnapshotV1 data,
            ResponseEvidence evidence,
            Map<String, String> headers,
            String tlsVersion,
            String serverSpiffeId) {
        public OwnerSuccess {
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(evidence, "evidence");
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
            exact(tlsVersion, "TLSv1.3", "tlsVersion");
            exact(serverSpiffeId, OWNER_SPIFFE, "serverSpiffeId");
            if (!data.asOf().equals(evidence.asOf())
                    || !data.validUntil().equals(evidence.validUntil())
                    || !data.scopeDigest().equals(evidence.scopeDigest())
                    || !data.contentSha256().equals(evidence.contentSha256())) {
                throw new IllegalArgumentException("data/evidence binding differs");
            }
        }
    }

    public record OwnerProblem(
            int httpStatus,
            String code,
            boolean retryable,
            UUID correlationId,
            Instant issuedAt,
            String errorDigest,
            String keyId,
            String signature,
            Map<String, String> headers,
            String tlsVersion,
            String serverSpiffeId) {
        public OwnerProblem {
            if (httpStatus < 400 || httpStatus > 599) {
                throw new IllegalArgumentException("httpStatus is invalid");
            }
            EmploymentAbsenceContract.code(code, "code");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(issuedAt, "issuedAt");
            digest(errorDigest, "errorDigest");
            bounded(keyId, 160, "keyId");
            compactJws(signature, 4096, "signature");
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
            exact(tlsVersion, "TLSv1.3", "tlsVersion");
            exact(serverSpiffeId, OWNER_SPIFFE, "serverSpiffeId");
        }
    }

    public sealed interface OwnerWireResult permits WireSuccess, WireProblem, WireFailure {
    }

    public record WireSuccess(OwnerSuccess value) implements OwnerWireResult {
        public WireSuccess { Objects.requireNonNull(value, "value"); }
    }

    public record WireProblem(OwnerProblem value) implements OwnerWireResult {
        public WireProblem { Objects.requireNonNull(value, "value"); }
    }

    /** Transport-generated, unknown or malformed responses never choose permanence. */
    public record WireFailure(String sanitizedCode) implements OwnerWireResult {
        public WireFailure { code(sanitizedCode, "sanitizedCode"); }
    }

    public record ResponseSigningKey(
            String keyId,
            String ownerSpiffeId,
            String bindingId,
            String purpose,
            boolean active,
            PublicKey publicKey) {
        public ResponseSigningKey {
            bounded(keyId, 160, "keyId");
            exact(ownerSpiffeId, OWNER_SPIFFE, "ownerSpiffeId");
            exact(bindingId, BINDING_ID, "bindingId");
            exact(purpose, OWNER_RESPONSE_KEY_PURPOSE, "purpose");
            Objects.requireNonNull(publicKey, "publicKey");
        }
    }

    public record OwnerFailure(
            OwnerFailureKind kind,
            String code,
            boolean exhaustionExempt) {
        public OwnerFailure {
            Objects.requireNonNull(kind, "kind");
            EmploymentAbsenceContract.code(code, "code");
            if (exhaustionExempt && kind != OwnerFailureKind.CONTEXT_REFRESH) {
                throw new IllegalArgumentException("only context refresh is exhaustion exempt");
            }
        }

        public static OwnerFailure retryable(String code) {
            return new OwnerFailure(OwnerFailureKind.RETRYABLE, code, false);
        }

        public static OwnerFailure refresh(String code) {
            return new OwnerFailure(OwnerFailureKind.CONTEXT_REFRESH, code, true);
        }

        public static OwnerFailure permanent(String code) {
            return new OwnerFailure(OwnerFailureKind.PERMANENT, code, false);
        }
    }

    public sealed interface OwnerResult permits OwnerRecovered, OwnerDenied {
    }

    public record OwnerRecovered(
            Xcon027EmploymentAbsenceSnapshotV1 snapshot,
            ResponseEvidence evidence,
            OwnerGrant grant) implements OwnerResult {
        public OwnerRecovered {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(grant, "grant");
        }
    }

    public record OwnerDenied(OwnerFailure failure) implements OwnerResult {
        public OwnerDenied { Objects.requireNonNull(failure, "failure"); }
    }

    public interface OwnerGrantAuthority {
        Optional<OwnerGrant> current(long tenantId);
    }

    public interface TenantContextAuthority {
        Optional<TenantContext> current(long tenantId);
    }

    public interface OwnerProofIssuer {
        Optional<OwnerRequestProof> issue(
                OwnerGrant grant,
                UUID relationshipPublicId,
                long minimumRelationshipRevision,
                LocalDate effectiveOn,
                UUID correlationId);
    }

    public interface OwnerTransport {
        OwnerWireResult exchange(OwnerRequestProof proof);
    }

    public interface ResponseKeyAuthority {
        Optional<ResponseSigningKey> current(String keyId);
    }

    public interface OwnerAudit {
        boolean append(long tenantId, UUID relationshipPublicId,
                UUID correlationId, String outcome, String digest,
                Map<String, Object> evidence);
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

    static void bounded(String value, int maximum, String field) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.length() > maximum || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    static void digest(String value, String field) {
        if (value == null || !DIGEST.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a digest");
        }
    }

    static void code(String value, String field) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a canonical code");
        }
    }

    static void compactJws(String value, int maximum, String field) {
        bounded(value, maximum, field);
        String[] segments = value.split("\\.", -1);
        if (segments.length != 3 || segments[0].isEmpty()
                || segments[1].isEmpty() || segments[2].isEmpty()) {
            throw new IllegalArgumentException(field + " is not compact JWS");
        }
    }

    static void canonicalZone(String value) {
        bounded(value, 80, "businessTimeZone");
        if (!"UTC".equals(value)
                && (!ZoneRulesProvider.getAvailableZoneIds().contains(value)
                || ZoneId.of(value).getRules().isFixedOffset())) {
            throw new IllegalArgumentException("businessTimeZone is not canonical");
        }
    }

    static Set<String> closedProblemCodes() {
        return Set.of(
                "DATE_ROLLED", "OWNER_AUTHORITY_STALE",
                "OWNER_REVISION_BELOW_MINIMUM", "OWNER_RATE_LIMITED",
                "OWNER_TEMPORARILY_UNAVAILABLE", "INVALID_REQUEST_BINDING",
                "INVALID_WORKLOAD_PROOF", "FORBIDDEN_OWNER_SCOPE",
                "OWNER_RELATIONSHIP_NOT_FOUND", "OWNER_CONFIGURATION_INVALID",
                "OWNER_CONTRACT_INVALID");
    }
}
