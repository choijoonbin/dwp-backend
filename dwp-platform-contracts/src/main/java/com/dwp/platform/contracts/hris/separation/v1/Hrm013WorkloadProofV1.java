package com.dwp.platform.contracts.hris.separation.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofViews.*;
import static com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofValues.*;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofVerification.Envelope;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofVerification.ProofParts;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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

    private static final Hrm013WorkloadProofValues VALUES = new Hrm013WorkloadProofValues(
            code -> failure(FailureCode.valueOf(code)), VerificationException.class::isInstance);
    private static final Hrm013WorkloadProofJson JSON = new Hrm013WorkloadProofJson(VALUES);
    private static final Hrm013WorkloadProofVerification VERIFICATION =
            new Hrm013WorkloadProofVerification(VALUES, JSON);

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

    public enum TaskOwner implements OwnerView {
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

    public enum ResolverCaller implements ResolverView {
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

    public record Header(String name, String value) implements HeaderView {
        public Header {
            Hrm013WorkloadProofModelValidation.headerFields(VALUES, name, value);
        }
    }

    /** Identity derived from the validated TLS leaf, never from a request header or claim. */
    public record TransportIdentity(String workloadId, String leafCertificateSha256) implements TransportView {
        public TransportIdentity {
            VALUES.requireServiceId(workloadId, "workloadId");
            VALUES.requireThumbprint(leafCertificateSha256, "leafCertificateSha256");
        }
    }

    /** Public verification material resolved from the sealed current or drain-only authority. */
    public record VerifiedSigningKey(PublicKey publicKey, String keyId, String workloadId) implements SigningKeyView {
        public VerifiedSigningKey {
            Hrm013WorkloadProofModelValidation.verifiedSigningKeyFields(VALUES, publicKey, keyId, workloadId);
        }
    }

    public record DrainClaims(
            UUID drainRootWorkItemId,
            Instant drainRootCreatedAt,
            String drainRootPayloadSha256,
            DrainContinuationKind drainContinuationKind,
            UUID drainContinuationEventId,
            long drainContinuationRevision,
            String drainContinuationPayloadSha256) implements DrainView {
        public DrainClaims {
            Hrm013WorkloadProofModelValidation.drainClaimsFields(
                    VALUES, drainRootWorkItemId, drainRootCreatedAt, drainRootPayloadSha256, drainContinuationKind,
                    drainContinuationEventId, drainContinuationRevision, drainContinuationPayloadSha256);
        }
    }

    public sealed interface AuthorityMode extends AuthorityView permits BusinessAuthority, ResolverAuthority {
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
            Hrm013WorkloadProofModelValidation.resolverAuthorityFields(VALUES, resolutionMode, tenantGrantRevision, authorizationRevision);
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
            DrainClaims drainClaims) implements SelectorView<AuthorityMode, DrainClaims> {
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
    public static final class Profile implements ProfileView {
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
            VALUES.requireProfile(bindingId, callerWorkloadId, ownerWorkloadId, purpose,
                    exactPath, maximumBodyBytes, profileClaimTypes, expectedProfileClaims, allowedDrainKinds);
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
        return profile(Hrm013WorkloadProofProfiles.apr002());
    }

    public static Profile hrm008(
            TaskOwner owner, UUID casePublicId, UUID taskPublicId) {
        return profile(Hrm013WorkloadProofProfiles.hrm008(owner, casePublicId, taskPublicId));
    }

    public static Profile hrm009(TaskOwner owner, UUID casePublicId) {
        return profile(Hrm013WorkloadProofProfiles.hrm009(owner, casePublicId));
    }

    public static Profile hrm010(
            UUID casePublicId, UUID taskPublicId, WaiverEvidenceMode evidenceMode) {
        return profile(Hrm013WorkloadProofProfiles.hrm010(casePublicId, taskPublicId, evidenceMode == null ? null : evidenceMode.name()));
    }

    public static Profile requesterIdentity() {
        return profile(Hrm013WorkloadProofProfiles.requesterIdentity());
    }

    public static Profile taskDispatch(TaskOwner owner) {
        return profile(Hrm013WorkloadProofProfiles.taskDispatch(owner));
    }

    public static Profile resolverBootstrap(ResolverCaller caller) {
        return profile(Hrm013WorkloadProofProfiles.resolverBootstrap(caller));
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
        VERIFICATION.requireRequest(mapper, clock, profile, actualMethod, actualPath,
                exactBodyBytes, transportIdentity, keyResolver, replayStore, authorityRechecker);

        Envelope envelope = VERIFICATION.validateEnvelope(requestHeaders, profile, transportIdentity);
        ProofParts<UntrustedSelector> proof = VERIFICATION.parseUntrustedProof(
                mapper, envelope.compactProof(), profile, envelope.correlationId(),
                (ignored, claims) -> parseSelector(profile, claims));
        VerifiedSigningKey key;
        try {
            key = keyResolver.resolve(profile, proof.selector(), proof.keyId());
        } catch (RuntimeException exception) {
            throw failure(FailureCode.SIGNING_KEY_UNTRUSTED);
        }
        VERIFICATION.requireSigningKey(key, proof.keyId(), profile);
        VERIFICATION.verifySignature(proof, key);

        ProofClaims claims = VERIFICATION.parseAndValidateClaims(
                profile, proof, envelope.correlationId(), exactBodyBytes,
                transportIdentity, clock.instant(), ProofClaims::new);
        // Business JSON is decoded only after the complete proof signature and claim set pass.
        JSON.strictCanonicalObject(
                mapper, exactBodyBytes, profile.maximumBodyBytes(),
                FailureCode.REQUEST_BODY_INVALID);

        ReplayReservation reservation = new ReplayReservation(
                claims.tenantId(), profile.bindingId(), profile.callerWorkloadId(),
                VALUES.sha256(claims.proofId().toString().getBytes(StandardCharsets.US_ASCII)),
                VALUES.sha256(claims.requestNonce().toString().getBytes(StandardCharsets.US_ASCII)),
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
                VALUES.sha256(envelope.compactProof().getBytes(StandardCharsets.US_ASCII)),
                VALUES.commandDigest(profile, claims.tenantId(), claims.requestBodySha256()));
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
        return VALUES.commandDigest(profile, tenantId, requestBodySha256);
    }

    public static String sha256(byte[] bytes) {
        return VALUES.sha256(bytes);
    }

    /** Strict duplicate-free RFC 8785 canonical bytes for HRM-013 digest preimages. */
    public static byte[] rfc8785CanonicalBytes(ObjectMapper mapper, byte[] jsonBytes) {
        return JSON.rfc8785CanonicalBytes(mapper, jsonBytes);
    }

    public static String rfc8785Sha256(ObjectMapper mapper, byte[] jsonBytes) {
        return VALUES.sha256(JSON.rfc8785CanonicalBytes(mapper, jsonBytes));
    }

    /** Deterministic terminal-child identity from the frozen HRM-013 UUIDv5 rule. */
    public static UUID terminalChildEventId(
            long tenantId,
            UUID drainRootWorkItemId,
            long drainContinuationRevision,
            String childBindingId) {
        return VALUES.terminalChildEventId(tenantId, drainRootWorkItemId, drainContinuationRevision, childBindingId);
    }

    private static UntrustedSelector parseSelector(Profile profile, JsonNode claims) {
        try {
            long tenantId = VALUES.positiveLong(claims, "tenantId", FailureCode.SELECTOR_INVALID);
            AuthorityMode authorityMode;
            if (profile.kind() == ProfileKind.BUSINESS) {
                GrantUseMode mode = VALUES.enumValue(
                        GrantUseMode.class,
                        VALUES.requiredText(claims, "grantUseMode", FailureCode.SELECTOR_INVALID),
                        FailureCode.SELECTOR_INVALID);
                authorityMode = new BusinessAuthority(
                        mode,
                        VALUES.positiveLong(claims, "tenantGrantRevision",
                                FailureCode.SELECTOR_INVALID),
                        VALUES.positiveLong(claims, "authorizationRevision",
                                FailureCode.SELECTOR_INVALID));
            } else {
                ResolutionMode mode = VALUES.enumValue(
                        ResolutionMode.class,
                        VALUES.requiredText(claims, "resolutionMode", FailureCode.SELECTOR_INVALID),
                        FailureCode.SELECTOR_INVALID);
                long tenantRevision = VALUES.nonNegativeLong(
                        claims, "signedTenantGrantRevision", FailureCode.SELECTOR_INVALID);
                long authorizationRevision = VALUES.nonNegativeLong(
                        claims, "signedAuthorizationRevision", FailureCode.SELECTOR_INVALID);
                authorityMode = new ResolverAuthority(
                        mode, tenantRevision, authorizationRevision);
            }
            DrainClaims drain = authorityMode.drainExisting()
                    ? VERIFICATION.parseDrainClaims(claims, FailureCode.SELECTOR_INVALID,
                            DrainContinuationKind.class, DrainClaims::new) : null;
            return new UntrustedSelector(tenantId, authorityMode, drain);
        } catch (VerificationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(FailureCode.SELECTOR_INVALID);
        }
    }

    private static VerificationException failure(FailureCode code) {
        return new VerificationException(code);
    }

    private static Profile profile(Hrm013WorkloadProofProfiles.ProfileSpec spec) {
        Map<String, ClaimType> fields = new LinkedHashMap<>();
        spec.fields().forEach((name, type) -> fields.put(name, ClaimType.valueOf(type)));
        Set<DrainContinuationKind> drainKinds = new HashSet<>();
        spec.drainKinds().forEach(kind -> drainKinds.add(DrainContinuationKind.valueOf(kind)));
        return new Profile(ProfileKind.valueOf(spec.kind()), spec.bindingId(),
                spec.caller(), spec.owner(), spec.purpose(), spec.path(),
                spec.maximumBodyBytes(), fields, spec.expected(), drainKinds);
    }
}
