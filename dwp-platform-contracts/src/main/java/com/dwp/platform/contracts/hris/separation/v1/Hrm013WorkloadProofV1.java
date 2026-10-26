package com.dwp.platform.contracts.hris.separation.v1;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Closed HRM-013 workload-proof profiles and fail-closed RS256 verification primitives.
 *
 * <p>The TLS connector remains responsible for TLS 1.3, trust-chain, revocation and exact
 * SPIFFE URI-SAN validation. This class accepts only the already authenticated leaf identity,
 * then binds it to the five-header HTTP envelope, the compact JWS, the request bytes and the
 * current (or drain-only) service-grant key. It deliberately contains no HTTP, database,
 * credential or tenant-grant implementation.
 */
public final class Hrm013WorkloadProofV1 {

    public static final String CONTENT_TYPE_HEADER = "Content-Type";
    public static final String ACCEPT_HEADER = "Accept";
    public static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    public static final String WORKLOAD_PROOF_HEADER = "X-DWP-Workload-Proof";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String JSON_MEDIA_TYPE = "application/json";

    public static final int MAXIMUM_JWS_BYTES = 16_384;
    public static final int MAXIMUM_SINGLE_APPLICATION_HEADER_BYTES = 16_384;
    public static final int MAXIMUM_AGGREGATE_APPLICATION_HEADER_BYTES = 32_768;
    public static final long MAXIMUM_PROOF_LIFETIME_SECONDS = 30;
    public static final long MAXIMUM_CLOCK_SKEW_SECONDS = 5;
    public static final int MINIMUM_RSA_BITS = 2_048;

    private static final int MAXIMUM_JOSE_BYTES = 1_024;
    private static final int MAXIMUM_CLAIMS_BYTES = 14_336;
    private static final int MAXIMUM_SIGNATURE_BYTES = 1_024;
    private static final long MAXIMUM_SAFE_JSON_INTEGER = 9_007_199_254_740_991L;
    private static final UUID TERMINAL_CHILD_NAMESPACE =
            UUID.fromString("8ca8c32b-c8f7-5ab4-a9a5-18166dc32128");

    private static final Set<String> EXACT_HEADER_NAMES = Set.of(
            "content-type", "accept", "x-dwp-service-identity",
            "x-dwp-workload-proof", "x-correlation-id");
    private static final Set<String> JOSE_FIELDS = Set.of("alg", "typ", "kid");
    private static final Set<String> COMMON_CLAIM_FIELDS = Set.of(
            "iss", "aud", "sub", "iat", "nbf", "exp", "jti", "requestNonce",
            "bindingId", "purpose", "method", "path", "tenantId", "correlationId",
            "requestBodySha256", "authn", "cnf");
    private static final Set<String> BUSINESS_AUTHORITY_FIELDS = Set.of(
            "tenantGrantRevision", "authorizationRevision", "grantUseMode");
    private static final Set<String> RESOLVER_AUTHORITY_FIELDS = Set.of(
            "resolutionMode", "signedTenantGrantRevision", "signedAuthorizationRevision");
    private static final Set<String> DRAIN_FIELDS = Set.of(
            "drainRootWorkItemId", "drainRootCreatedAt", "drainRootPayloadSha256",
            "drainContinuationKind", "drainContinuationEventId",
            "drainContinuationRevision", "drainContinuationPayloadSha256");
    private static final Set<String> RESERVED_CLAIM_FIELDS;

    private static final Pattern KEY_ID =
            Pattern.compile("^[A-Za-z0-9_-][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern SERVICE_ID =
            Pattern.compile("^dwp-[a-z][a-z0-9-]{1,62}$");
    private static final Pattern BINDING_ID =
            Pattern.compile("^SVC-[A-Z]{3}-[A-Z]{3}-[0-9]{3}$");
    private static final Pattern PURPOSE =
            Pattern.compile("^[A-Z][A-Z0-9_]{2,119}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern HEADER_NAME =
            Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");
    private static final Pattern PROFILE_CLAIM_NAME =
            Pattern.compile("^[a-z][A-Za-z0-9]{1,79}$");

    static {
        Set<String> reserved = new HashSet<>(COMMON_CLAIM_FIELDS);
        reserved.addAll(BUSINESS_AUTHORITY_FIELDS);
        reserved.addAll(RESOLVER_AUTHORITY_FIELDS);
        reserved.addAll(DRAIN_FIELDS);
        RESERVED_CLAIM_FIELDS = Set.copyOf(reserved);
    }

    private Hrm013WorkloadProofV1() {
    }

    public enum ProfileKind {
        BUSINESS,
        RESOLVER_BOOTSTRAP
    }

    public enum GrantUseMode {
        NEW_WORK,
        DRAIN_EXISTING
    }

    public enum ResolutionMode {
        NEW_WORK,
        DRAIN_EXISTING,
        READINESS_OBSERVE,
        PROBE_MISSING
    }

    public enum DrainContinuationKind {
        ROOT_DELIVERY,
        DEPENDENT_READ,
        TERMINAL_CHILD
    }

    public enum WaiverEvidenceMode {
        APPROVED,
        REJECTED
    }

    public enum ClaimType {
        UUID,
        POSITIVE_LONG,
        NON_NEGATIVE_LONG,
        SHA256,
        INSTANT,
        IDENTIFIER,
        CODE,
        TEXT
    }

    public enum TaskOwner {
        TIME(
                "dwp-time-server",
                "TIME_FINALIZATION",
                "SVC-PEP-TIM-004",
                "HRIS_SEPARATION_TIME_TASK_DISPATCH",
                "/internal/hris-time/v1/separation-task-dispatches",
                "HRIS_SEPARATION_TIME_TASK_RECEIPT"),
        PAYROLL(
                "dwp-payroll-server",
                "PAYROLL_SETTLEMENT",
                "SVC-PEP-PAY-002",
                "HRIS_SEPARATION_PAYROLL_TASK_DISPATCH",
                "/internal/hris-payroll/v1/separation-task-dispatches",
                "HRIS_SEPARATION_PAYROLL_TASK_RECEIPT"),
        IDENTITY(
                "dwp-auth-server",
                "IDENTITY_ACCESS_REVOCATION",
                "SVC-AUT-HRM-014",
                "HRIS_SEPARATION_IDENTITY_TASK_DISPATCH",
                "/internal/auth/v1/separation-task-dispatches",
                "HRIS_SEPARATION_IDENTITY_TASK_RECEIPT");

        private final String workloadId;
        private final String taskType;
        private final String dispatchBindingId;
        private final String dispatchPurpose;
        private final String dispatchPath;
        private final String receiptPurpose;

        TaskOwner(
                String workloadId,
                String taskType,
                String dispatchBindingId,
                String dispatchPurpose,
                String dispatchPath,
                String receiptPurpose) {
            this.workloadId = workloadId;
            this.taskType = taskType;
            this.dispatchBindingId = dispatchBindingId;
            this.dispatchPurpose = dispatchPurpose;
            this.dispatchPath = dispatchPath;
            this.receiptPurpose = receiptPurpose;
        }

        public String workloadId() {
            return workloadId;
        }

        public String taskType() {
            return taskType;
        }

        public String dispatchBindingId() {
            return dispatchBindingId;
        }

        public String dispatchPurpose() {
            return dispatchPurpose;
        }

        public String dispatchPath() {
            return dispatchPath;
        }

        public String receiptPurpose() {
            return receiptPurpose;
        }
    }

    public enum ResolverCaller {
        PEOPLE("SVC-AUT-HRM-011", "dwp-people-server"),
        APPROVAL("SVC-AUT-HRM-012", "dwp-approval-server"),
        TIME("SVC-AUT-HRM-015", "dwp-time-server"),
        PAYROLL("SVC-AUT-HRM-016", "dwp-payroll-server");

        private final String bindingId;
        private final String workloadId;

        ResolverCaller(String bindingId, String workloadId) {
            this.bindingId = bindingId;
            this.workloadId = workloadId;
        }

        public String bindingId() {
            return bindingId;
        }

        public String workloadId() {
            return workloadId;
        }
    }

    public enum FailureCode {
        EXACT_METHOD_OR_PATH_REQUIRED,
        REQUEST_BODY_INVALID,
        REQUEST_BODY_TOO_LARGE,
        EXACT_FIVE_HEADER_ENVELOPE_REQUIRED,
        AUTHORIZATION_HEADER_FORBIDDEN,
        APPLICATION_HEADER_FORBIDDEN,
        DUPLICATE_OR_INVALID_HEADER,
        APPLICATION_HEADER_TOO_LARGE,
        EXACT_JSON_MEDIA_HEADERS_REQUIRED,
        SERVICE_IDENTITY_MISMATCH,
        CORRELATION_ID_INVALID,
        COMPACT_JWS_INVALID,
        JOSE_HEADER_INVALID,
        JOSE_PROFILE_INVALID,
        KEY_ID_INVALID,
        CLAIMS_INVALID,
        CLAIM_SET_INVALID,
        SELECTOR_INVALID,
        SIGNING_KEY_UNTRUSTED,
        RS256_KEY_REQUIRED,
        SIGNATURE_INVALID,
        EXACT_CLAIMS_REQUIRED,
        CERTIFICATE_CONFIRMATION_MISMATCH,
        FRESHNESS_INVALID,
        GRANT_MODE_INVALID,
        DRAIN_CLAIMS_INVALID,
        PROFILE_CLAIMS_INVALID,
        PROOF_REPLAYED,
        AUTHORITY_CHANGED
    }

    public static final class VerificationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final FailureCode code;

        private VerificationException(FailureCode code) {
            super(code.name());
            this.code = code;
        }

        public FailureCode code() {
            return code;
        }
    }

    public record Header(String name, String value) {
        public Header {
            if (name == null || value == null || name.isBlank()
                    || !HEADER_NAME.matcher(name).matches()
                    || hasLineBreak(name) || hasLineBreak(value)) {
                throw new IllegalArgumentException("header must be a valid single-line field");
            }
        }
    }

    /** Identity derived from the validated TLS leaf, never from a request header or claim. */
    public record TransportIdentity(String workloadId, String leafCertificateSha256) {
        public TransportIdentity {
            requireServiceId(workloadId, "workloadId");
            requireThumbprint(leafCertificateSha256, "leafCertificateSha256");
        }
    }

    /** Public verification material resolved from the sealed current or drain-only authority. */
    public record VerifiedSigningKey(PublicKey publicKey, String keyId, String workloadId) {
        public VerifiedSigningKey {
            Objects.requireNonNull(publicKey, "publicKey");
            if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
                throw new IllegalArgumentException("keyId is not canonical");
            }
            requireServiceId(workloadId, "workloadId");
        }
    }

    public record DrainClaims(
            UUID drainRootWorkItemId,
            Instant drainRootCreatedAt,
            String drainRootPayloadSha256,
            DrainContinuationKind drainContinuationKind,
            UUID drainContinuationEventId,
            long drainContinuationRevision,
            String drainContinuationPayloadSha256) {
        public DrainClaims {
            Objects.requireNonNull(drainRootWorkItemId, "drainRootWorkItemId");
            Objects.requireNonNull(drainRootCreatedAt, "drainRootCreatedAt");
            Objects.requireNonNull(drainContinuationKind, "drainContinuationKind");
            Objects.requireNonNull(drainContinuationEventId, "drainContinuationEventId");
            requireDigest(drainRootPayloadSha256, "drainRootPayloadSha256");
            requireDigest(drainContinuationPayloadSha256,
                    "drainContinuationPayloadSha256");
            if (drainContinuationRevision < 0) {
                throw new IllegalArgumentException(
                        "drainContinuationRevision must be non-negative");
            }
        }
    }

    public sealed interface AuthorityMode permits BusinessAuthority, ResolverAuthority {
        long tenantGrantRevision();

        long authorizationRevision();

        boolean drainExisting();
    }

    public record BusinessAuthority(
            GrantUseMode grantUseMode,
            long tenantGrantRevision,
            long authorizationRevision) implements AuthorityMode {
        public BusinessAuthority {
            Objects.requireNonNull(grantUseMode, "grantUseMode");
            if (tenantGrantRevision < 1 || authorizationRevision < 1) {
                throw new IllegalArgumentException("business grant revisions must be positive");
            }
        }

        @Override
        public boolean drainExisting() {
            return grantUseMode == GrantUseMode.DRAIN_EXISTING;
        }
    }

    public record ResolverAuthority(
            ResolutionMode resolutionMode,
            long tenantGrantRevision,
            long authorizationRevision) implements AuthorityMode {
        public ResolverAuthority {
            Objects.requireNonNull(resolutionMode, "resolutionMode");
            boolean probe = resolutionMode == ResolutionMode.PROBE_MISSING;
            if ((probe && (tenantGrantRevision != 0 || authorizationRevision != 0))
                    || (!probe && (tenantGrantRevision < 1 || authorizationRevision < 1))) {
                throw new IllegalArgumentException("resolver revision pair does not match mode");
            }
        }

        @Override
        public boolean drainExisting() {
            return resolutionMode == ResolutionMode.DRAIN_EXISTING;
        }
    }

    /** Bounded, untrusted selector passed only to the authority-key resolver. */
    public record UntrustedSelector(
            long tenantId,
            AuthorityMode authorityMode,
            DrainClaims drainClaims) {
        public UntrustedSelector {
            if (tenantId < 1) {
                throw new IllegalArgumentException("tenantId must be positive");
            }
            Objects.requireNonNull(authorityMode, "authorityMode");
            if (authorityMode.drainExisting() != (drainClaims != null)) {
                throw new IllegalArgumentException("drain fields do not match mode");
            }
        }
    }

    public record ProofClaims(
            String issuer,
            String audience,
            String subject,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt,
            UUID proofId,
            UUID requestNonce,
            String bindingId,
            String purpose,
            String method,
            String path,
            long tenantId,
            UUID correlationId,
            String requestBodySha256,
            String authentication,
            String certificateThumbprint,
            String keyId,
            AuthorityMode authorityMode,
            DrainClaims drainClaims,
            Map<String, Object> profileClaims) {
        public ProofClaims {
            profileClaims = Map.copyOf(profileClaims);
        }

        public GrantUseMode grantUseMode() {
            return authorityMode instanceof BusinessAuthority business
                    ? business.grantUseMode() : null;
        }

        public ResolutionMode resolutionMode() {
            return authorityMode instanceof ResolverAuthority resolver
                    ? resolver.resolutionMode() : null;
        }

        public long tenantGrantRevision() {
            return authorityMode.tenantGrantRevision();
        }

        public long authorizationRevision() {
            return authorityMode.authorizationRevision();
        }
    }

    public record ReplayReservation(
            long tenantId,
            String bindingId,
            String callerWorkloadId,
            String proofIdSha256,
            String requestNonceSha256,
            Instant retainUntil) {
    }

    public record VerifiedProof(
            Profile profile,
            ProofClaims claims,
            String proofSha256,
            String commandDigest) {
    }

    @FunctionalInterface
    public interface SigningKeyResolver {
        VerifiedSigningKey resolve(Profile profile, UntrustedSelector selector, String keyId);
    }

    /** Implementations reserve both digests atomically in the tenant replay namespace. */
    @FunctionalInterface
    public interface ReplayStore {
        boolean reserve(ReplayReservation reservation);
    }

    /** Post-signature, post-replay unchanged-authority check. */
    @FunctionalInterface
    public interface AuthorityRechecker {
        boolean remainsCurrent(Profile profile, VerifiedSigningKey key, ProofClaims claims);
    }

    /** Immutable, closed proof profile. Instances are obtainable only from the sealed factories. */
    public static final class Profile {
        private final ProfileKind kind;
        private final String bindingId;
        private final String callerWorkloadId;
        private final String ownerWorkloadId;
        private final String purpose;
        private final String exactPath;
        private final int maximumBodyBytes;
        private final Map<String, ClaimType> profileClaimTypes;
        private final Map<String, Object> expectedProfileClaims;
        private final Set<DrainContinuationKind> allowedDrainKinds;

        private Profile(
                ProfileKind kind,
                String bindingId,
                String callerWorkloadId,
                String ownerWorkloadId,
                String purpose,
                String exactPath,
                int maximumBodyBytes,
                Map<String, ClaimType> profileClaimTypes,
                Map<String, Object> expectedProfileClaims,
                Set<DrainContinuationKind> allowedDrainKinds) {
            this.kind = Objects.requireNonNull(kind, "kind");
            requireBindingId(bindingId);
            requireServiceId(callerWorkloadId, "callerWorkloadId");
            requireServiceId(ownerWorkloadId, "ownerWorkloadId");
            requirePurpose(purpose);
            requirePath(exactPath);
            if (maximumBodyBytes < 1 || maximumBodyBytes > 32_768) {
                throw new IllegalArgumentException("maximumBodyBytes is outside the sealed bound");
            }
            Objects.requireNonNull(profileClaimTypes, "profileClaimTypes");
            Objects.requireNonNull(expectedProfileClaims, "expectedProfileClaims");
            Objects.requireNonNull(allowedDrainKinds, "allowedDrainKinds");
            for (Map.Entry<String, ClaimType> entry : profileClaimTypes.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null
                        || !PROFILE_CLAIM_NAME.matcher(entry.getKey()).matches()
                        || RESERVED_CLAIM_FIELDS.contains(entry.getKey())) {
                    throw new IllegalArgumentException("invalid or reserved profile claim");
                }
            }
            if (!profileClaimTypes.keySet().containsAll(expectedProfileClaims.keySet())) {
                throw new IllegalArgumentException("expected claim is not declared by profile");
            }
            this.bindingId = bindingId;
            this.callerWorkloadId = callerWorkloadId;
            this.ownerWorkloadId = ownerWorkloadId;
            this.purpose = purpose;
            this.exactPath = exactPath;
            this.maximumBodyBytes = maximumBodyBytes;
            this.profileClaimTypes = Map.copyOf(profileClaimTypes);
            this.expectedProfileClaims = Map.copyOf(expectedProfileClaims);
            this.allowedDrainKinds = Set.copyOf(allowedDrainKinds);
        }

        public ProfileKind kind() {
            return kind;
        }

        public String bindingId() {
            return bindingId;
        }

        public String callerWorkloadId() {
            return callerWorkloadId;
        }

        public String ownerWorkloadId() {
            return ownerWorkloadId;
        }

        public String purpose() {
            return purpose;
        }

        public String method() {
            return "POST";
        }

        public String exactPath() {
            return exactPath;
        }

        public int maximumBodyBytes() {
            return maximumBodyBytes;
        }

        public Map<String, ClaimType> profileClaimTypes() {
            return profileClaimTypes;
        }

        public Map<String, Object> expectedProfileClaims() {
            return expectedProfileClaims;
        }

        public Set<DrainContinuationKind> allowedDrainKinds() {
            return allowedDrainKinds;
        }

        public String authentication() {
            return kind == ProfileKind.RESOLVER_BOOTSTRAP
                    ? "MTLS_LEAF_RS256" : "MTLS_SPIFFE_RS256";
        }
    }

    public static Profile apr002() {
        return businessProfile(
                "SVC-PEP-APR-002", "dwp-people-server", "dwp-approval-server",
                "HRIS_SEPARATION_TASK_WAIVER_REQUEST",
                "/internal/hris-approval/v1/separation-task-waiver-requests", 32_768,
                Map.of(
                        "approvalRequestId", ClaimType.UUID,
                        "casePublicId", ClaimType.UUID,
                        "taskPublicId", ClaimType.UUID),
                Map.of(), Set.of(DrainContinuationKind.ROOT_DELIVERY));
    }

    public static Profile hrm008(
            TaskOwner owner, UUID casePublicId, UUID taskPublicId) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(casePublicId, "casePublicId");
        Objects.requireNonNull(taskPublicId, "taskPublicId");
        return businessProfile(
                "SVC-PEP-HRM-008", owner.workloadId(), "dwp-people-server",
                owner.receiptPurpose(),
                "/internal/hris-people/v1/separation-cases/" + casePublicId
                        + "/tasks/" + taskPublicId + "/receipts",
                32_768,
                Map.of(
                        "callbackEventId", ClaimType.UUID,
                        "casePublicId", ClaimType.UUID,
                        "taskPublicId", ClaimType.UUID,
                        "ownerRevision", ClaimType.POSITIVE_LONG,
                        "originatingPayloadSha256", ClaimType.SHA256),
                Map.of("casePublicId", casePublicId, "taskPublicId", taskPublicId),
                Set.of(DrainContinuationKind.ROOT_DELIVERY,
                        DrainContinuationKind.TERMINAL_CHILD));
    }

    public static Profile hrm009(TaskOwner owner, UUID casePublicId) {
        Objects.requireNonNull(owner, "owner");
        if (owner == TaskOwner.IDENTITY) {
            throw new IllegalArgumentException("identity is not an HRM-009 reconciliation owner");
        }
        Objects.requireNonNull(casePublicId, "casePublicId");
        String purpose = owner == TaskOwner.TIME
                ? "HRIS_SEPARATION_TIME_RECONCILIATION"
                : "HRIS_SEPARATION_PAYROLL_RECONCILIATION";
        return businessProfile(
                "SVC-PEP-HRM-009", owner.workloadId(), "dwp-people-server", purpose,
                "/internal/hris-people/v1/separation-cases/" + casePublicId
                        + "/reconciliation-results",
                32_768,
                Map.of(
                        "callbackEventId", ClaimType.UUID,
                        "casePublicId", ClaimType.UUID,
                        "ownerRevision", ClaimType.POSITIVE_LONG,
                        "originatingPayloadSha256", ClaimType.SHA256),
                Map.of("casePublicId", casePublicId),
                Set.of(DrainContinuationKind.ROOT_DELIVERY));
    }

    public static Profile hrm010(
            UUID casePublicId, UUID taskPublicId, WaiverEvidenceMode evidenceMode) {
        Objects.requireNonNull(casePublicId, "casePublicId");
        Objects.requireNonNull(taskPublicId, "taskPublicId");
        Objects.requireNonNull(evidenceMode, "evidenceMode");
        Map<String, ClaimType> fields = new LinkedHashMap<>();
        fields.put("callbackEventId", ClaimType.UUID);
        fields.put("casePublicId", ClaimType.UUID);
        fields.put("taskPublicId", ClaimType.UUID);
        fields.put("approvalRequestId", ClaimType.UUID);
        fields.put("ownerRevision", ClaimType.POSITIVE_LONG);
        fields.put("originatingPayloadSha256", ClaimType.SHA256);
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("casePublicId", casePublicId);
        expected.put("taskPublicId", taskPublicId);
        if (evidenceMode == WaiverEvidenceMode.APPROVED) {
            fields.put("stepUpEvidenceId", ClaimType.UUID);
            fields.put("stepUpEvidenceAudience", ClaimType.IDENTIFIER);
            fields.put("stepUpEvidencePurpose", ClaimType.CODE);
            fields.put("stepUpEvidenceNonce", ClaimType.UUID);
            fields.put("stepUpEvidenceDigest", ClaimType.SHA256);
            fields.put("stepUpVerifiedAt", ClaimType.INSTANT);
            fields.put("stepUpConsumedAt", ClaimType.INSTANT);
            expected.put("stepUpEvidenceAudience", "dwp-people-server");
            expected.put("stepUpEvidencePurpose",
                    "HRIS_SEPARATION_TASK_WAIVER_DECISION");
        }
        return businessProfile(
                "SVC-PEP-HRM-010", "dwp-approval-server", "dwp-people-server",
                "HRIS_SEPARATION_TASK_WAIVER_DECISION",
                "/internal/hris-people/v1/separation-cases/" + casePublicId
                        + "/tasks/" + taskPublicId + "/waiver-decisions",
                32_768, fields, expected,
                Set.of(DrainContinuationKind.ROOT_DELIVERY,
                        DrainContinuationKind.TERMINAL_CHILD));
    }

    public static Profile requesterIdentity() {
        return businessProfile(
                "SVC-AUT-HRM-013", "dwp-approval-server", "dwp-auth-server",
                "HRIS_SEPARATION_REQUESTER_IDENTITY_RESOLVE",
                "/internal/auth/v1/hris-separation-requester-authority/resolve", 8_192,
                Map.of(
                        "approvalRequestId", ClaimType.UUID,
                        "requestedByActorPublicId", ClaimType.UUID),
                Map.of(), Set.of(DrainContinuationKind.DEPENDENT_READ));
    }

    public static Profile taskDispatch(TaskOwner owner) {
        Objects.requireNonNull(owner, "owner");
        return businessProfile(
                owner.dispatchBindingId(), "dwp-people-server", owner.workloadId(),
                owner.dispatchPurpose(), owner.dispatchPath(), 32_768,
                Map.of(
                        "dispatchEventId", ClaimType.UUID,
                        "casePublicId", ClaimType.UUID,
                        "taskPublicId", ClaimType.UUID,
                        "taskType", ClaimType.CODE,
                        "taskOwnerRevision", ClaimType.POSITIVE_LONG,
                        "payloadSha256", ClaimType.SHA256),
                Map.of("taskType", owner.taskType()),
                Set.of(DrainContinuationKind.ROOT_DELIVERY));
    }

    public static Profile resolverBootstrap(ResolverCaller caller) {
        Objects.requireNonNull(caller, "caller");
        return new Profile(
                ProfileKind.RESOLVER_BOOTSTRAP, caller.bindingId(), caller.workloadId(),
                "dwp-auth-server", "HRIS_SERVICE_GRANT_AUTHORITY_RESOLVE",
                "/internal/auth/v1/hris-service-grant-authority/resolve", 8_192,
                Map.of(
                        "requestingWorkloadId", ClaimType.IDENTIFIER,
                        "targetCallerWorkloadId", ClaimType.IDENTIFIER,
                        "targetOwnerWorkloadId", ClaimType.IDENTIFIER,
                        "targetBindingId", ClaimType.IDENTIFIER,
                        "targetPurpose", ClaimType.CODE),
                Map.of("requestingWorkloadId", caller.workloadId()),
                Set.of(DrainContinuationKind.values()));
    }

    private static Profile businessProfile(
            String bindingId,
            String caller,
            String owner,
            String purpose,
            String path,
            int maximumBodyBytes,
            Map<String, ClaimType> fields,
            Map<String, Object> expected,
            Set<DrainContinuationKind> drainKinds) {
        return new Profile(ProfileKind.BUSINESS, bindingId, caller, owner, purpose, path,
                maximumBodyBytes, fields, expected, drainKinds);
    }

    public static VerifiedProof verify(
            ObjectMapper mapper,
            Clock clock,
            Profile profile,
            String actualMethod,
            String actualPath,
            List<Header> requestHeaders,
            byte[] exactBodyBytes,
            TransportIdentity transportIdentity,
            SigningKeyResolver keyResolver,
            ReplayStore replayStore,
            AuthorityRechecker authorityRechecker) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(exactBodyBytes, "exactBodyBytes");
        Objects.requireNonNull(transportIdentity, "transportIdentity");
        Objects.requireNonNull(keyResolver, "keyResolver");
        Objects.requireNonNull(replayStore, "replayStore");
        Objects.requireNonNull(authorityRechecker, "authorityRechecker");

        if (!profile.method().equals(actualMethod)
                || !profile.exactPath().equals(actualPath)
                || actualPath.indexOf('?') >= 0 || actualPath.indexOf('#') >= 0) {
            throw failure(FailureCode.EXACT_METHOD_OR_PATH_REQUIRED);
        }
        if (exactBodyBytes.length == 0) {
            throw failure(FailureCode.REQUEST_BODY_INVALID);
        }
        if (exactBodyBytes.length > profile.maximumBodyBytes()) {
            throw failure(FailureCode.REQUEST_BODY_TOO_LARGE);
        }

        Envelope envelope = validateEnvelope(requestHeaders, profile, transportIdentity);
        ProofParts proof = parseUntrustedProof(
                mapper, envelope.compactProof(), profile, envelope.correlationId());
        VerifiedSigningKey key;
        try {
            key = keyResolver.resolve(profile, proof.selector(), proof.keyId());
        } catch (RuntimeException exception) {
            throw failure(FailureCode.SIGNING_KEY_UNTRUSTED);
        }
        requireSigningKey(key, proof.keyId(), profile);
        verifySignature(proof, key);

        ProofClaims claims = parseAndValidateClaims(
                profile, proof, envelope.correlationId(), exactBodyBytes,
                transportIdentity, clock.instant());
        // Business JSON is decoded only after the complete proof signature and claim set pass.
        strictCanonicalObject(
                mapper, exactBodyBytes, profile.maximumBodyBytes(),
                FailureCode.REQUEST_BODY_INVALID);

        ReplayReservation reservation = new ReplayReservation(
                claims.tenantId(), profile.bindingId(), profile.callerWorkloadId(),
                sha256(claims.proofId().toString().getBytes(StandardCharsets.US_ASCII)),
                sha256(claims.requestNonce().toString().getBytes(StandardCharsets.US_ASCII)),
                claims.expiresAt().plusSeconds(MAXIMUM_CLOCK_SKEW_SECONDS));
        boolean reserved;
        try {
            reserved = replayStore.reserve(reservation);
        } catch (RuntimeException exception) {
            reserved = false;
        }
        if (!reserved) {
            throw failure(FailureCode.PROOF_REPLAYED);
        }
        boolean current;
        try {
            current = authorityRechecker.remainsCurrent(profile, key, claims);
        } catch (RuntimeException exception) {
            current = false;
        }
        if (!current) {
            throw failure(FailureCode.AUTHORITY_CHANGED);
        }
        return new VerifiedProof(
                profile, claims,
                sha256(envelope.compactProof().getBytes(StandardCharsets.US_ASCII)),
                commandDigest(profile, claims.tenantId(), claims.requestBodySha256()));
    }

    public static List<Header> headers(
            Profile profile, String compactProof, UUID correlationId) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(correlationId, "correlationId");
        return List.of(
                new Header(CONTENT_TYPE_HEADER, JSON_MEDIA_TYPE),
                new Header(ACCEPT_HEADER, JSON_MEDIA_TYPE),
                new Header(SERVICE_IDENTITY_HEADER, profile.callerWorkloadId()),
                new Header(WORKLOAD_PROOF_HEADER, compactProof),
                new Header(CORRELATION_ID_HEADER, correlationId.toString()));
    }

    public static String commandDigest(
            Profile profile, long tenantId, String requestBodySha256) {
        Objects.requireNonNull(profile, "profile");
        if (tenantId < 1) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        requireDigest(requestBodySha256, "requestBodySha256");
        String preimage = String.join("\n",
                profile.bindingId(), profile.method(), profile.exactPath(),
                profile.callerWorkloadId(), profile.ownerWorkloadId(),
                Long.toString(tenantId), profile.purpose(), JSON_MEDIA_TYPE,
                requestBodySha256);
        return sha256(preimage.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Strict duplicate-free RFC 8785 canonical bytes for HRM-013 digest preimages. */
    public static byte[] rfc8785CanonicalBytes(ObjectMapper mapper, byte[] jsonBytes) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(jsonBytes, "jsonBytes");
        JsonNode node = readJson(mapper, jsonBytes, Integer.MAX_VALUE,
                FailureCode.REQUEST_BODY_INVALID, false, false);
        ByteArrayOutputStream output = new ByteArrayOutputStream(jsonBytes.length);
        writeCanonical(node, output);
        return output.toByteArray();
    }

    public static String rfc8785Sha256(ObjectMapper mapper, byte[] jsonBytes) {
        return sha256(rfc8785CanonicalBytes(mapper, jsonBytes));
    }

    /** Deterministic terminal-child identity from the frozen HRM-013 UUIDv5 rule. */
    public static UUID terminalChildEventId(
            long tenantId,
            UUID drainRootWorkItemId,
            long drainContinuationRevision,
            String childBindingId) {
        if (tenantId < 1 || drainContinuationRevision < 1) {
            throw new IllegalArgumentException("terminal child revisions must be positive");
        }
        Objects.requireNonNull(drainRootWorkItemId, "drainRootWorkItemId");
        requireBindingId(childBindingId);
        String name = tenantId + "\n" + drainRootWorkItemId + "\n"
                + drainContinuationRevision + "\n" + childBindingId
                + "\nTERMINAL_CHILD";
        return uuidV5(TERMINAL_CHILD_NAMESPACE, name.getBytes(StandardCharsets.UTF_8));
    }

    private static Envelope validateEnvelope(
            List<Header> requestHeaders,
            Profile profile,
            TransportIdentity transportIdentity) {
        if (requestHeaders == null) {
            throw failure(FailureCode.EXACT_FIVE_HEADER_ENVELOPE_REQUIRED);
        }
        Map<String, String> normalized = new HashMap<>();
        int aggregateBytes = 0;
        for (Header header : requestHeaders) {
            if (header == null) {
                throw failure(FailureCode.DUPLICATE_OR_INVALID_HEADER);
            }
            String name = header.name().toLowerCase(Locale.ROOT);
            if ("authorization".equals(name)) {
                throw failure(FailureCode.AUTHORIZATION_HEADER_FORBIDDEN);
            }
            if (!EXACT_HEADER_NAMES.contains(name)) {
                throw failure(FailureCode.APPLICATION_HEADER_FORBIDDEN);
            }
            if (!header.value().equals(header.value().trim()) || header.value().isEmpty()
                    || normalized.putIfAbsent(name, header.value()) != null) {
                throw failure(FailureCode.DUPLICATE_OR_INVALID_HEADER);
            }
            int fieldBytes = header.name().getBytes(StandardCharsets.US_ASCII).length
                    + header.value().getBytes(StandardCharsets.UTF_8).length;
            if (fieldBytes > MAXIMUM_SINGLE_APPLICATION_HEADER_BYTES) {
                throw failure(FailureCode.APPLICATION_HEADER_TOO_LARGE);
            }
            aggregateBytes = Math.addExact(aggregateBytes, fieldBytes);
        }
        if (requestHeaders.size() != 5 || !normalized.keySet().equals(EXACT_HEADER_NAMES)) {
            throw failure(FailureCode.EXACT_FIVE_HEADER_ENVELOPE_REQUIRED);
        }
        if (aggregateBytes > MAXIMUM_AGGREGATE_APPLICATION_HEADER_BYTES) {
            throw failure(FailureCode.APPLICATION_HEADER_TOO_LARGE);
        }
        if (!JSON_MEDIA_TYPE.equals(normalized.get("content-type"))
                || !JSON_MEDIA_TYPE.equals(normalized.get("accept"))) {
            throw failure(FailureCode.EXACT_JSON_MEDIA_HEADERS_REQUIRED);
        }
        if (!profile.callerWorkloadId().equals(normalized.get("x-dwp-service-identity"))
                || !profile.callerWorkloadId().equals(transportIdentity.workloadId())) {
            throw failure(FailureCode.SERVICE_IDENTITY_MISMATCH);
        }
        UUID correlation = canonicalUuid(
                normalized.get("x-correlation-id"), FailureCode.CORRELATION_ID_INVALID);
        String compact = normalized.get("x-dwp-workload-proof");
        if (compact.getBytes(StandardCharsets.US_ASCII).length > MAXIMUM_JWS_BYTES) {
            throw failure(FailureCode.COMPACT_JWS_INVALID);
        }
        return new Envelope(compact, correlation);
    }

    private static ProofParts parseUntrustedProof(
            ObjectMapper mapper,
            String compact,
            Profile profile,
            UUID envelopeCorrelationId) {
        if (compact == null || !StandardCharsets.US_ASCII.newEncoder().canEncode(compact)) {
            throw failure(FailureCode.COMPACT_JWS_INVALID);
        }
        String[] segments = compact.split("\\.", -1);
        if (segments.length != 3 || segments[0].isEmpty()
                || segments[1].isEmpty() || segments[2].isEmpty()) {
            throw failure(FailureCode.COMPACT_JWS_INVALID);
        }
        byte[] joseBytes = decodeCanonicalBase64Url(
                segments[0], MAXIMUM_JOSE_BYTES, FailureCode.JOSE_HEADER_INVALID);
        byte[] claimBytes = decodeCanonicalBase64Url(
                segments[1], MAXIMUM_CLAIMS_BYTES, FailureCode.CLAIMS_INVALID);
        byte[] signatureBytes = decodeCanonicalBase64Url(
                segments[2], MAXIMUM_SIGNATURE_BYTES, FailureCode.COMPACT_JWS_INVALID);
        JsonNode jose = strictCanonicalObject(
                mapper, joseBytes, MAXIMUM_JOSE_BYTES, FailureCode.JOSE_HEADER_INVALID);
        requireExactFields(jose, JOSE_FIELDS, FailureCode.JOSE_HEADER_INVALID);
        if (!"RS256".equals(requiredText(jose, "alg", FailureCode.JOSE_HEADER_INVALID))
                || !"JWT".equals(requiredText(jose, "typ", FailureCode.JOSE_HEADER_INVALID))) {
            throw failure(FailureCode.JOSE_PROFILE_INVALID);
        }
        String keyId = requiredText(jose, "kid", FailureCode.JOSE_HEADER_INVALID);
        if (!KEY_ID.matcher(keyId).matches()) {
            throw failure(FailureCode.KEY_ID_INVALID);
        }
        JsonNode claims = strictCanonicalObject(
                mapper, claimBytes, MAXIMUM_CLAIMS_BYTES, FailureCode.CLAIMS_INVALID);
        UntrustedSelector selector = parseSelector(profile, claims);
        // Correlation is a selector only for exact envelope equality; it grants no authority.
        if (!envelopeCorrelationId.equals(canonicalUuid(
                requiredText(claims, "correlationId", FailureCode.SELECTOR_INVALID),
                FailureCode.SELECTOR_INVALID))) {
            throw failure(FailureCode.SELECTOR_INVALID);
        }
        return new ProofParts(
                segments[0], segments[1], signatureBytes, claims, keyId, selector);
    }

    private static UntrustedSelector parseSelector(Profile profile, JsonNode claims) {
        try {
            long tenantId = positiveLong(claims, "tenantId", FailureCode.SELECTOR_INVALID);
            AuthorityMode authorityMode;
            if (profile.kind() == ProfileKind.BUSINESS) {
                GrantUseMode mode = enumValue(
                        GrantUseMode.class,
                        requiredText(claims, "grantUseMode", FailureCode.SELECTOR_INVALID),
                        FailureCode.SELECTOR_INVALID);
                authorityMode = new BusinessAuthority(
                        mode,
                        positiveLong(claims, "tenantGrantRevision",
                                FailureCode.SELECTOR_INVALID),
                        positiveLong(claims, "authorizationRevision",
                                FailureCode.SELECTOR_INVALID));
            } else {
                ResolutionMode mode = enumValue(
                        ResolutionMode.class,
                        requiredText(claims, "resolutionMode", FailureCode.SELECTOR_INVALID),
                        FailureCode.SELECTOR_INVALID);
                long tenantRevision = nonNegativeLong(
                        claims, "signedTenantGrantRevision", FailureCode.SELECTOR_INVALID);
                long authorizationRevision = nonNegativeLong(
                        claims, "signedAuthorizationRevision", FailureCode.SELECTOR_INVALID);
                authorityMode = new ResolverAuthority(
                        mode, tenantRevision, authorizationRevision);
            }
            DrainClaims drain = authorityMode.drainExisting()
                    ? parseDrainClaims(claims, FailureCode.SELECTOR_INVALID) : null;
            return new UntrustedSelector(tenantId, authorityMode, drain);
        } catch (VerificationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(FailureCode.SELECTOR_INVALID);
        }
    }

    private static void requireSigningKey(
            VerifiedSigningKey key, String keyId, Profile profile) {
        if (key == null || !keyId.equals(key.keyId())
                || !profile.callerWorkloadId().equals(key.workloadId())) {
            throw failure(FailureCode.SIGNING_KEY_UNTRUSTED);
        }
        if (!(key.publicKey() instanceof RSAPublicKey rsa)
                || rsa.getModulus().bitLength() < MINIMUM_RSA_BITS) {
            throw failure(FailureCode.RS256_KEY_REQUIRED);
        }
    }

    private static void verifySignature(ProofParts proof, VerifiedSigningKey key) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key.publicKey());
            verifier.update((proof.encodedJose() + "." + proof.encodedClaims())
                    .getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(proof.signature())) {
                throw failure(FailureCode.SIGNATURE_INVALID);
            }
        } catch (VerificationException exception) {
            throw exception;
        } catch (GeneralSecurityException exception) {
            throw failure(FailureCode.SIGNATURE_INVALID);
        }
    }

    private static ProofClaims parseAndValidateClaims(
            Profile profile,
            ProofParts proof,
            UUID envelopeCorrelationId,
            byte[] exactBodyBytes,
            TransportIdentity transportIdentity,
            Instant now) {
        JsonNode claims = proof.claims();
        Set<String> expectedFields = new HashSet<>(COMMON_CLAIM_FIELDS);
        expectedFields.addAll(profile.kind() == ProfileKind.BUSINESS
                ? BUSINESS_AUTHORITY_FIELDS : RESOLVER_AUTHORITY_FIELDS);
        expectedFields.addAll(profile.profileClaimTypes().keySet());
        if (proof.selector().authorityMode().drainExisting()) {
            expectedFields.addAll(DRAIN_FIELDS);
        }
        requireExactFields(claims, expectedFields, FailureCode.CLAIM_SET_INVALID);

        String issuer = requiredText(claims, "iss", FailureCode.CLAIMS_INVALID);
        String audience = requiredText(claims, "aud", FailureCode.CLAIMS_INVALID);
        String subject = requiredText(claims, "sub", FailureCode.CLAIMS_INVALID);
        Instant issuedAt = epochSecond(claims, "iat");
        Instant notBefore = epochSecond(claims, "nbf");
        Instant expiresAt = epochSecond(claims, "exp");
        UUID proofId = canonicalUuid(
                requiredText(claims, "jti", FailureCode.CLAIMS_INVALID),
                FailureCode.CLAIMS_INVALID);
        UUID requestNonce = canonicalUuid(
                requiredText(claims, "requestNonce", FailureCode.CLAIMS_INVALID),
                FailureCode.CLAIMS_INVALID);
        UUID correlationId = canonicalUuid(
                requiredText(claims, "correlationId", FailureCode.CLAIMS_INVALID),
                FailureCode.CLAIMS_INVALID);
        String bodySha256 = requiredDigest(claims, "requestBodySha256");
        String authentication = requiredText(claims, "authn", FailureCode.CLAIMS_INVALID);
        String certificateThumbprint = parseConfirmation(claims.get("cnf"));

        if (!profile.callerWorkloadId().equals(issuer)
                || !profile.callerWorkloadId().equals(subject)
                || !profile.ownerWorkloadId().equals(audience)
                || !profile.bindingId().equals(requiredText(
                        claims, "bindingId", FailureCode.CLAIMS_INVALID))
                || !profile.purpose().equals(requiredText(
                        claims, "purpose", FailureCode.CLAIMS_INVALID))
                || !profile.method().equals(requiredText(
                        claims, "method", FailureCode.CLAIMS_INVALID))
                || !profile.exactPath().equals(requiredText(
                        claims, "path", FailureCode.CLAIMS_INVALID))
                || proof.selector().tenantId() != positiveLong(
                        claims, "tenantId", FailureCode.CLAIMS_INVALID)
                || !envelopeCorrelationId.equals(correlationId)
                || !sha256(exactBodyBytes).equals(bodySha256)
                || !profile.authentication().equals(authentication)) {
            throw failure(FailureCode.EXACT_CLAIMS_REQUIRED);
        }
        if (!transportIdentity.leafCertificateSha256().equals(certificateThumbprint)
                || (profile.kind() == ProfileKind.RESOLVER_BOOTSTRAP
                && !proof.keyId().equals(certificateThumbprint))) {
            throw failure(FailureCode.CERTIFICATE_CONFIRMATION_MISMATCH);
        }
        requireFreshness(issuedAt, notBefore, expiresAt, proofId, requestNonce, now);

        Map<String, Object> profileClaims = parseProfileClaims(profile, claims);
        DrainClaims drain = proof.selector().drainClaims();
        validateDrainClaims(
                profile, proof.selector().tenantId(), profileClaims, drain);
        return new ProofClaims(
                issuer, audience, subject, issuedAt, notBefore, expiresAt,
                proofId, requestNonce, profile.bindingId(), profile.purpose(),
                profile.method(), profile.exactPath(), proof.selector().tenantId(),
                correlationId, bodySha256, authentication, certificateThumbprint,
                proof.keyId(), proof.selector().authorityMode(), drain, profileClaims);
    }

    private static void requireFreshness(
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt,
            UUID proofId,
            UUID requestNonce,
            Instant now) {
        long lifetime;
        try {
            lifetime = Math.subtractExact(
                    expiresAt.getEpochSecond(), issuedAt.getEpochSecond());
        } catch (ArithmeticException exception) {
            throw failure(FailureCode.FRESHNESS_INVALID);
        }
        if (!issuedAt.equals(notBefore)
                || proofId.equals(requestNonce)
                || lifetime < 1 || lifetime > MAXIMUM_PROOF_LIFETIME_SECONDS
                || issuedAt.isAfter(now.plusSeconds(MAXIMUM_CLOCK_SKEW_SECONDS))
                || !expiresAt.isAfter(now.minusSeconds(MAXIMUM_CLOCK_SKEW_SECONDS))) {
            throw failure(FailureCode.FRESHNESS_INVALID);
        }
    }

    private static DrainClaims parseDrainClaims(JsonNode claims, FailureCode code) {
        try {
            return new DrainClaims(
                    canonicalUuid(requiredText(claims, "drainRootWorkItemId", code), code),
                    canonicalInstant(requiredText(claims, "drainRootCreatedAt", code), code),
                    requiredDigest(claims, "drainRootPayloadSha256"),
                    enumValue(
                            DrainContinuationKind.class,
                            requiredText(claims, "drainContinuationKind", code), code),
                    canonicalUuid(
                            requiredText(claims, "drainContinuationEventId", code), code),
                    nonNegativeLong(claims, "drainContinuationRevision", code),
                    requiredDigest(claims, "drainContinuationPayloadSha256"));
        } catch (VerificationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(code);
        }
    }

    private static void validateDrainClaims(
            Profile profile,
            long tenantId,
            Map<String, Object> profileClaims,
            DrainClaims drain) {
        if (drain == null) {
            return;
        }
        if (!profile.allowedDrainKinds().contains(drain.drainContinuationKind())) {
            throw failure(FailureCode.DRAIN_CLAIMS_INVALID);
        }
        switch (drain.drainContinuationKind()) {
            case ROOT_DELIVERY, DEPENDENT_READ -> {
                if (!drain.drainRootWorkItemId().equals(drain.drainContinuationEventId())
                        || drain.drainContinuationRevision() != 0
                        || !drain.drainRootPayloadSha256().equals(
                                drain.drainContinuationPayloadSha256())) {
                    throw failure(FailureCode.DRAIN_CLAIMS_INVALID);
                }
            }
            case TERMINAL_CHILD -> {
                String childBindingId = profile.bindingId();
                if (profile.kind() == ProfileKind.RESOLVER_BOOTSTRAP) {
                    Object targetBindingId = profileClaims.get("targetBindingId");
                    if (!(targetBindingId instanceof String value)
                            || !BINDING_ID.matcher(value).matches()) {
                        throw failure(FailureCode.DRAIN_CLAIMS_INVALID);
                    }
                    childBindingId = value;
                }
                if (drain.drainContinuationRevision() < 1
                        || !terminalChildEventId(
                                tenantId, drain.drainRootWorkItemId(),
                                drain.drainContinuationRevision(), childBindingId)
                        .equals(drain.drainContinuationEventId())) {
                    throw failure(FailureCode.DRAIN_CLAIMS_INVALID);
                }
            }
        }
        if ("SVC-AUT-HRM-013".equals(profile.bindingId())) {
            Object approvalRequestId = profileClaims.get("approvalRequestId");
            if (!(approvalRequestId instanceof UUID rootId)
                    || !rootId.equals(drain.drainRootWorkItemId())
                    || !rootId.equals(drain.drainContinuationEventId())) {
                throw failure(FailureCode.DRAIN_CLAIMS_INVALID);
            }
        }
    }

    private static Map<String, Object> parseProfileClaims(
            Profile profile, JsonNode claims) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, ClaimType> entry : profile.profileClaimTypes().entrySet()) {
            String name = entry.getKey();
            Object value = switch (entry.getValue()) {
                case UUID -> canonicalUuid(
                        requiredText(claims, name, FailureCode.PROFILE_CLAIMS_INVALID),
                        FailureCode.PROFILE_CLAIMS_INVALID);
                case POSITIVE_LONG -> positiveLong(
                        claims, name, FailureCode.PROFILE_CLAIMS_INVALID);
                case NON_NEGATIVE_LONG -> nonNegativeLong(
                        claims, name, FailureCode.PROFILE_CLAIMS_INVALID);
                case SHA256 -> requiredDigest(claims, name);
                case INSTANT -> canonicalInstant(
                        requiredText(claims, name, FailureCode.PROFILE_CLAIMS_INVALID),
                        FailureCode.PROFILE_CLAIMS_INVALID);
                case IDENTIFIER -> boundedIdentifier(
                        requiredText(claims, name, FailureCode.PROFILE_CLAIMS_INVALID));
                case CODE -> boundedCode(
                        requiredText(claims, name, FailureCode.PROFILE_CLAIMS_INVALID));
                case TEXT -> boundedText(
                        requiredText(claims, name, FailureCode.PROFILE_CLAIMS_INVALID));
            };
            parsed.put(name, value);
        }
        for (Map.Entry<String, Object> expected : profile.expectedProfileClaims().entrySet()) {
            if (!Objects.equals(expected.getValue(), parsed.get(expected.getKey()))) {
                throw failure(FailureCode.PROFILE_CLAIMS_INVALID);
            }
        }
        return Map.copyOf(parsed);
    }

    private static String parseConfirmation(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw failure(FailureCode.CLAIMS_INVALID);
        }
        requireExactFields(node, Set.of("x5t#S256"), FailureCode.CLAIMS_INVALID);
        String thumbprint = requiredText(node, "x5t#S256", FailureCode.CLAIMS_INVALID);
        try {
            requireThumbprint(thumbprint, "cnf.x5t#S256");
            return thumbprint;
        } catch (IllegalArgumentException exception) {
            throw failure(FailureCode.CLAIMS_INVALID);
        }
    }

    private static JsonNode strictCanonicalObject(
            ObjectMapper mapper, byte[] bytes, int limit, FailureCode code) {
        JsonNode node = readJson(mapper, bytes, limit, code, true, true);
        if (!node.isObject()) {
            throw failure(code);
        }
        return node;
    }

    private static JsonNode readJson(
            ObjectMapper mapper,
            byte[] bytes,
            int limit,
            FailureCode code,
            boolean requireCanonical,
            boolean rejectNull) {
        if (bytes.length == 0 || bytes.length > limit) {
            throw failure(code);
        }
        try (JsonParser parser = mapper.getFactory().createParser(bytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = mapper.readTree(parser);
            if (node == null || parser.nextToken() != null
                    || (rejectNull && containsNull(node))) {
                throw failure(code);
            }
            byte[] canonical = canonicalBytes(node);
            if (requireCanonical && !Arrays.equals(bytes, canonical)) {
                throw failure(code);
            }
            return node;
        } catch (VerificationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure(code);
        }
    }

    private static boolean containsNull(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) {
            return true;
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                if (containsNull(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] canonicalBytes(JsonNode node) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeCanonical(node, output);
        return output.toByteArray();
    }

    private static void writeCanonical(JsonNode node, ByteArrayOutputStream output) {
        if (node.isObject()) {
            output.write('{');
            TreeMap<String, JsonNode> sorted = new TreeMap<>(Comparator.naturalOrder());
            node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
                if (!first) {
                    output.write(',');
                }
                first = false;
                writeCanonicalString(entry.getKey(), output);
                output.write(':');
                writeCanonical(entry.getValue(), output);
            }
            output.write('}');
            return;
        }
        if (node.isArray()) {
            output.write('[');
            boolean first = true;
            for (JsonNode child : node) {
                if (!first) {
                    output.write(',');
                }
                first = false;
                writeCanonical(child, output);
            }
            output.write(']');
            return;
        }
        if (node.isTextual()) {
            writeCanonicalString(node.textValue(), output);
            return;
        }
        if (node.isBoolean()) {
            writeAscii(node.booleanValue() ? "true" : "false", output);
            return;
        }
        if (node.isNull()) {
            writeAscii("null", output);
            return;
        }
        if (node.isNumber()) {
            writeAscii(canonicalNumber(node), output);
            return;
        }
        throw new IllegalArgumentException("unsupported JSON value in canonical preimage");
    }

    private static void writeCanonicalString(String value, ByteArrayOutputStream output) {
        validateUnicode(value);
        output.write('"');
        StringBuilder encoded = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> encoded.append("\\\"");
                case '\\' -> encoded.append("\\\\");
                case '\b' -> encoded.append("\\b");
                case '\t' -> encoded.append("\\t");
                case '\n' -> encoded.append("\\n");
                case '\f' -> encoded.append("\\f");
                case '\r' -> encoded.append("\\r");
                default -> {
                    if (character < 0x20) {
                        encoded.append("\\u00");
                        encoded.append(Character.forDigit((character >>> 4) & 0xf, 16));
                        encoded.append(Character.forDigit(character & 0xf, 16));
                    } else {
                        encoded.append(character);
                    }
                }
            }
        }
        byte[] bytes = encoded.toString().getBytes(StandardCharsets.UTF_8);
        output.writeBytes(bytes);
        output.write('"');
    }

    private static String canonicalNumber(JsonNode node) {
        if (node.isIntegralNumber()) {
            BigInteger integer = node.bigIntegerValue();
            if (integer.abs().compareTo(BigInteger.valueOf(MAXIMUM_SAFE_JSON_INTEGER)) > 0) {
                throw new IllegalArgumentException("integer is outside the I-JSON safe range");
            }
            return integer.toString();
        }
        double value = node.doubleValue();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("non-finite JSON number");
        }
        if (value == 0.0d) {
            return "0";
        }
        boolean negative = value < 0;
        String encoded = ecmaNumber(shortestRoundTrip(Math.abs(value)));
        return negative ? "-" + encoded : encoded;
    }

    private static BigDecimal shortestRoundTrip(double value) {
        BigDecimal javaShortest = new BigDecimal(
                Double.toString(value)).stripTrailingZeros();
        // Java requires at least two significant digits for some binary64 values;
        // ECMAScript/JCS permits the closer single-digit round-trip representation.
        if (javaShortest.precision() != 2) {
            return javaShortest;
        }
        BigDecimal exact = new BigDecimal(value);
        long expectedBits = Double.doubleToRawLongBits(value);
        BigDecimal lower = exact.round(
                new MathContext(1, RoundingMode.FLOOR)).stripTrailingZeros();
        BigDecimal upper = exact.round(
                new MathContext(1, RoundingMode.CEILING)).stripTrailingZeros();
        BigDecimal best = null;
        BigDecimal bestDistance = null;
        for (BigDecimal candidate : List.of(lower, upper)) {
            if (Double.doubleToRawLongBits(Double.parseDouble(candidate.toString()))
                    != expectedBits) {
                continue;
            }
            BigDecimal distance = exact.subtract(candidate).abs();
            if (best == null || distance.compareTo(bestDistance) < 0
                    || (distance.compareTo(bestDistance) == 0
                    && isEven(candidate) && !isEven(best))) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best == null ? javaShortest : best;
    }

    private static boolean isEven(BigDecimal value) {
        return !value.unscaledValue().abs().testBit(0);
    }

    private static String ecmaNumber(BigDecimal value) {
        String digits = value.unscaledValue().abs().toString();
        int position = digits.length() - value.scale();
        if (digits.length() <= position && position <= 21) {
            return digits + "0".repeat(position - digits.length());
        }
        if (position > 0 && position <= 21) {
            return digits.substring(0, position) + "." + digits.substring(position);
        }
        if (position > -6 && position <= 0) {
            return "0." + "0".repeat(-position) + digits;
        }
        StringBuilder scientific = new StringBuilder().append(digits.charAt(0));
        if (digits.length() > 1) {
            scientific.append('.').append(digits, 1, digits.length());
        }
        int exponent = position - 1;
        scientific.append('e');
        if (exponent >= 0) {
            scientific.append('+');
        }
        return scientific.append(exponent).toString();
    }

    private static void validateUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(++index))) {
                    throw new IllegalArgumentException("lone high surrogate");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("lone low surrogate");
            }
        }
    }

    private static void writeAscii(String value, ByteArrayOutputStream output) {
        output.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void requireExactFields(
            JsonNode object, Set<String> expected, FailureCode code) {
        Set<String> actual = new HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw failure(code);
        }
    }

    private static String requiredText(JsonNode node, String field, FailureCode code) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()
                || !value.textValue().equals(value.textValue().trim())
                || hasControl(value.textValue())) {
            throw failure(code);
        }
        return value.textValue();
    }

    private static long positiveLong(JsonNode node, String field, FailureCode code) {
        long value = exactLong(node, field, code);
        if (value < 1 || value > MAXIMUM_SAFE_JSON_INTEGER) {
            throw failure(code);
        }
        return value;
    }

    private static long nonNegativeLong(JsonNode node, String field, FailureCode code) {
        long value = exactLong(node, field, code);
        if (value < 0 || value > MAXIMUM_SAFE_JSON_INTEGER) {
            throw failure(code);
        }
        return value;
    }

    private static long exactLong(JsonNode node, String field, FailureCode code) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw failure(code);
        }
        return value.longValue();
    }

    private static Instant epochSecond(JsonNode node, String field) {
        long value = nonNegativeLong(node, field, FailureCode.CLAIMS_INVALID);
        try {
            return Instant.ofEpochSecond(value);
        } catch (RuntimeException exception) {
            throw failure(FailureCode.CLAIMS_INVALID);
        }
    }

    private static String requiredDigest(JsonNode node, String field) {
        String value = requiredText(node, field, FailureCode.CLAIMS_INVALID);
        if (!SHA256.matcher(value).matches()) {
            throw failure(FailureCode.CLAIMS_INVALID);
        }
        return value;
    }

    private static UUID canonicalUuid(String raw, FailureCode code) {
        try {
            UUID value = UUID.fromString(raw);
            if (!value.toString().equals(raw)) {
                throw failure(code);
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw failure(code);
        }
    }

    private static Instant canonicalInstant(String raw, FailureCode code) {
        try {
            Instant value = Instant.parse(raw);
            if (!value.toString().equals(raw) || !raw.endsWith("Z")) {
                throw failure(code);
            }
            return value;
        } catch (DateTimeParseException exception) {
            throw failure(code);
        }
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type, String raw, FailureCode code) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException exception) {
            throw failure(code);
        }
    }

    private static byte[] decodeCanonicalBase64Url(
            String raw, int maximumDecodedBytes, FailureCode code) {
        if (raw.isEmpty() || raw.indexOf('=') >= 0
                || !StandardCharsets.US_ASCII.newEncoder().canEncode(raw)) {
            throw failure(code);
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(raw);
            if (decoded.length == 0 || decoded.length > maximumDecodedBytes
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
                    .equals(raw)) {
                throw failure(code);
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw failure(code);
        }
    }

    private static String boundedIdentifier(String value) {
        if (value.length() > 120
                || !Pattern.matches("^[A-Za-z][A-Za-z0-9._:-]{1,119}$", value)) {
            throw failure(FailureCode.PROFILE_CLAIMS_INVALID);
        }
        return value;
    }

    private static String boundedCode(String value) {
        if (value.length() > 120 || !PURPOSE.matcher(value).matches()) {
            throw failure(FailureCode.PROFILE_CLAIMS_INVALID);
        }
        return value;
    }

    private static String boundedText(String value) {
        if (value.length() > 512 || value.isBlank() || hasControl(value)) {
            throw failure(FailureCode.PROFILE_CLAIMS_INVALID);
        }
        return value;
    }

    private static void requirePath(String path) {
        if (path == null || path.length() > 512 || !path.startsWith("/internal/")
                || path.indexOf('?') >= 0 || path.indexOf('#') >= 0
                || path.endsWith("/") || hasControl(path)) {
            throw new IllegalArgumentException("path is not an exact internal path");
        }
    }

    private static void requireServiceId(String value, String field) {
        if (value == null || !SERVICE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a canonical service ID");
        }
    }

    private static void requireBindingId(String value) {
        if (value == null || !BINDING_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("bindingId is not canonical");
        }
    }

    private static void requirePurpose(String value) {
        if (value == null || !PURPOSE.matcher(value).matches()) {
            throw new IllegalArgumentException("purpose is not canonical");
        }
    }

    private static void requireDigest(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a lowercase SHA-256 digest");
        }
    }

    private static void requireThumbprint(String value, String field) {
        if (value == null || value.indexOf('=') >= 0) {
            throw new IllegalArgumentException(field + " is not a certificate thumbprint");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length != 32
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
                    .equals(value)) {
                throw new IllegalArgumentException(
                        field + " is not a certificate thumbprint");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    field + " is not a certificate thumbprint", exception);
        }
    }

    private static boolean hasLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static boolean hasControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static UUID uuidV5(UUID namespace, byte[] name) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(uuidBytes(namespace));
            byte[] bytes = Arrays.copyOf(digest.digest(name), 16);
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }

    private static byte[] uuidBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    private static VerificationException failure(FailureCode code) {
        return new VerificationException(code);
    }

    private record Envelope(String compactProof, UUID correlationId) {
    }

    private record ProofParts(
            String encodedJose,
            String encodedClaims,
            byte[] signature,
            JsonNode claims,
            String keyId,
            UntrustedSelector selector) {
        private ProofParts {
            signature = signature.clone();
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }
    }
}
