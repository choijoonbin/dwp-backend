package com.dwp.platform.contracts.hris.home;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Generic fail-closed admission proof for the four owner-side Home materializers.
 *
 * <p>This is intentionally independent from command/approval proofs: Home
 * materialization is an authenticated GET with a signed delegated read context,
 * no request body, and no browser or caller-selected tenant authority.
 */
public final class HomeMaterializationWorkloadProofV1 {

    public static final String CALLER_WORKLOAD_ID = "dwp-platform-server";
    public static final String PURPOSE = HrisHomeContributionMaterializationV1.PURPOSE;
    public static final String METHOD = "GET";
    public static final String WORKLOAD_PROOF_HEADER = "X-DWP-Workload-Proof";
    public static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";

    private static final Set<String> JOSE_FIELDS = Set.of("alg", "typ", "kid");
    private static final Set<String> CLAIM_FIELDS = Set.of(
            "iss", "aud", "sub", "iat", "nbf", "exp", "jti", "requestNonce",
            "purpose", "method", "path", "tenantId", "subjectPrincipalPublicId",
            "correlationId", "capability", "ownerPopulationScope", "scopeRevision",
            "populationScopeDigest", "effectivePermissionRevision",
            "fieldPolicyRevision", "widgetKey", "browserAudience", "authn");
    private static final Set<String> RECOGNIZED_DWP_HEADERS = Set.of(
            "x-dwp-workload-proof", "x-dwp-service-identity");
    private static final Set<String> PROHIBITED_HEADERS = Set.of(
            "authorization", "cookie", "origin", "referer",
            "x-forwarded-user", "x-forwarded-email", "x-real-ip",
            "x-dwp-user-id", "x-dwp-person-public-id", "x-dwp-subject-public-id",
            "x-dwp-provider-tenant-id", "x-dwp-actor-tenant-id",
            "x-dwp-support-session-id");
    private static final Pattern KID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$");
    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern CAPABILITY =
            Pattern.compile("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+){1,15}$");
    private static final Pattern PAYLOAD_REF =
            Pattern.compile("^href_[A-Za-z0-9_-]{16,128}$");

    private HomeMaterializationWorkloadProofV1() {
    }

    public enum WorkloadAuthentication {
        MTLS_SPIFFE,
        CLIENT_CREDENTIAL_JWT
    }

    public enum Endpoint {
        PEOPLE(HrisHomeContributionMaterializationV1.Module.HRM,
                "dwp-people-server", "/internal/hris-people/v1/home-contributions/",
                Pattern.compile("^people-[a-z0-9-]{1,33}$")),
        PERFORMANCE(HrisHomeContributionMaterializationV1.Module.PER,
                "dwp-people-server", "/internal/hris-performance/v1/home-contributions/",
                Pattern.compile("^performance-[a-z0-9-]{1,28}$")),
        TIME_LEAVE(HrisHomeContributionMaterializationV1.Module.TIM,
                "dwp-time-server", "/internal/hris-time/v1/home-contributions/",
                Pattern.compile("^(?:time|leave)-[a-z0-9-]{1,31}$")),
        PAYROLL(HrisHomeContributionMaterializationV1.Module.PAY,
                "dwp-payroll-server", "/internal/hris-payroll/v1/home-contributions/",
                Pattern.compile("^payroll-[a-z0-9-]{1,30}$"));

        private final HrisHomeContributionMaterializationV1.Module module;
        private final String ownerAudience;
        private final String pathPrefix;
        private final Pattern widgetKey;

        Endpoint(HrisHomeContributionMaterializationV1.Module module,
                String ownerAudience, String pathPrefix, Pattern widgetKey) {
            this.module = module;
            this.ownerAudience = ownerAudience;
            this.pathPrefix = pathPrefix;
            this.widgetKey = widgetKey;
        }

        public HrisHomeContributionMaterializationV1.Module module() {
            return module;
        }

        public String ownerAudience() {
            return ownerAudience;
        }

        public String path(String payloadRef) {
            if (payloadRef == null || !PAYLOAD_REF.matcher(payloadRef).matches()) {
                throw denied("CANONICAL_PAYLOAD_REFERENCE_REQUIRED");
            }
            return pathPrefix + payloadRef;
        }

        private boolean matches(String exactPath) {
            if (exactPath == null || !exactPath.startsWith(pathPrefix)
                    || exactPath.indexOf('?') >= 0 || exactPath.indexOf('#') >= 0) {
                return false;
            }
            String reference = exactPath.substring(pathPrefix.length());
            return PAYLOAD_REF.matcher(reference).matches() && path(reference).equals(exactPath);
        }

        private boolean admitsContext(ProofClaims claims) {
            return widgetKey.matcher(claims.widgetKey()).matches()
                    && claims.ownerPopulationScope().matches(claims.browserAudience(), module);
        }
    }

    public record Header(String name, String value) {
        public Header {
            if (name == null || name.isBlank() || value == null
                    || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("header name and value must be single-line");
            }
        }
    }

    /** Trust-store result; workload identity and authentication are never claim-derived. */
    public record VerifiedSigningKey(
            PublicKey publicKey,
            WorkloadAuthentication authentication,
            String workloadId) {
        public VerifiedSigningKey {
            Objects.requireNonNull(publicKey, "publicKey");
            Objects.requireNonNull(authentication, "authentication");
            Objects.requireNonNull(workloadId, "workloadId");
        }
    }

    @FunctionalInterface
    public interface SigningKeyResolver {
        VerifiedSigningKey resolve(String keyId);
    }

    /** Owner implementation re-evaluates tenant grant, subject, capability and all revisions. */
    @FunctionalInterface
    public interface CurrentAuthorityVerifier {
        boolean admits(Endpoint endpoint, ProofClaims signedContext);
    }

    /** Durable implementations reserve the JTI and nonce atomically until expiry. */
    @FunctionalInterface
    public interface ReplayStore {
        boolean reserve(UUID proofId, UUID requestNonce, long tenantId, Instant expiresAt);
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
            String purpose,
            String method,
            String path,
            long tenantId,
            UUID subjectPrincipalPublicId,
            UUID correlationId,
            String capability,
            HrisHomeContributionMaterializationV1.OwnerPopulationScope ownerPopulationScope,
            long scopeRevision,
            String populationScopeDigest,
            long effectivePermissionRevision,
            long fieldPolicyRevision,
            String widgetKey,
            HrisHomeContributionMaterializationV1.BrowserAudience browserAudience,
            WorkloadAuthentication authentication,
            String keyId) {
    }

    public record VerifiedAdmission(Endpoint endpoint, ProofClaims claims) {
        public long tenantId() {
            return claims.tenantId();
        }

        public UUID subjectPrincipalPublicId() {
            return claims.subjectPrincipalPublicId();
        }
    }

    public static VerifiedAdmission verify(
            ObjectMapper mapper,
            Clock clock,
            Endpoint endpoint,
            String exactPath,
            List<Header> requestHeaders,
            SigningKeyResolver keyResolver,
            CurrentAuthorityVerifier authorityVerifier,
            ReplayStore replayStore) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(keyResolver, "keyResolver");
        Objects.requireNonNull(authorityVerifier, "authorityVerifier");
        Objects.requireNonNull(replayStore, "replayStore");
        if (!endpoint.matches(exactPath)) {
            throw denied("EXACT_HOME_MATERIALIZATION_GET_PATH_REQUIRED");
        }

        Map<String, String> headers = validateHeaders(requestHeaders);
        if (!CALLER_WORKLOAD_ID.equals(headers.get("x-dwp-service-identity"))) {
            throw denied("CALLER_SERVICE_IDENTITY_MISMATCH");
        }
        ProofParts parts = parseAndVerifyProof(
                mapper, headers.get("x-dwp-workload-proof"), keyResolver);
        ProofClaims claims = parseClaims(parts.claims(), parts.keyId());
        requireExactClaims(endpoint, exactPath, claims, clock.instant(), parts.key());
        if (!authorityVerifier.admits(endpoint, claims)) {
            throw denied("CURRENT_HOME_AUTHORITY_REQUIRED");
        }
        if (!replayStore.reserve(
                claims.proofId(), claims.requestNonce(), claims.tenantId(), claims.expiresAt())) {
            throw denied("HOME_WORKLOAD_PROOF_REPLAYED");
        }
        return new VerifiedAdmission(endpoint, claims);
    }

    private static Map<String, String> validateHeaders(List<Header> requestHeaders) {
        if (requestHeaders == null) throw denied("REQUEST_HEADER_EVIDENCE_REQUIRED");
        Map<String, String> normalized = new HashMap<>();
        for (Header header : requestHeaders) {
            if (header == null) throw denied("DUPLICATE_OR_INVALID_HEADER_EVIDENCE");
            String name = header.name().toLowerCase(Locale.ROOT);
            if (normalized.putIfAbsent(name, header.value()) != null) {
                throw denied("DUPLICATE_OR_INVALID_HEADER_EVIDENCE");
            }
            if (isProhibited(name)) {
                throw denied("BROWSER_END_USER_OR_TENANT_HEADER_FORBIDDEN");
            }
            if (name.startsWith("x-dwp-") && !RECOGNIZED_DWP_HEADERS.contains(name)) {
                throw denied("UNRECOGNIZED_DWP_HEADER_FORBIDDEN");
            }
        }
        if (!normalized.containsKey("x-dwp-service-identity")
                || !normalized.containsKey("x-dwp-workload-proof")) {
            throw denied("WORKLOAD_HEADERS_REQUIRED");
        }
        return Map.copyOf(normalized);
    }

    private static boolean isProhibited(String name) {
        return PROHIBITED_HEADERS.contains(name)
                || name.startsWith("sec-fetch-")
                || name.startsWith("sec-ch-ua")
                || name.startsWith("x-dwp-tenant")
                || name.startsWith("x-dwp-browser")
                || name.startsWith("x-dwp-delegated-");
    }

    private static ProofParts parseAndVerifyProof(
            ObjectMapper mapper, String compact, SigningKeyResolver resolver) {
        String[] segments = compact == null ? new String[0] : compact.split("\\.", -1);
        if (segments.length != 3 || segments[0].isEmpty()
                || segments[1].isEmpty() || segments[2].isEmpty()) {
            throw denied("COMPACT_WORKLOAD_PROOF_REQUIRED");
        }
        byte[] headerBytes = decodeCanonicalBase64Url(segments[0]);
        byte[] claimBytes = decodeCanonicalBase64Url(segments[1]);
        byte[] signatureBytes = decodeCanonicalBase64Url(segments[2]);
        JsonNode header = strictObject(mapper, headerBytes, JOSE_FIELDS, "JOSE_HEADER_INVALID");
        if (!"RS256".equals(requiredText(header, "alg"))
                || !"JWT".equals(requiredText(header, "typ"))) {
            throw denied("JOSE_PROFILE_INVALID");
        }
        String keyId = requiredText(header, "kid");
        if (!KID.matcher(keyId).matches()) throw denied("JOSE_KEY_ID_INVALID");
        VerifiedSigningKey key;
        try {
            key = resolver.resolve(keyId);
        } catch (RuntimeException exception) {
            throw denied("WORKLOAD_SIGNING_KEY_UNTRUSTED");
        }
        if (key == null) throw denied("WORKLOAD_SIGNING_KEY_UNTRUSTED");
        if (!(key.publicKey() instanceof RSAPublicKey rsa)
                || rsa.getModulus().bitLength() < 2048) {
            throw denied("RS256_SIGNING_KEY_REQUIRED");
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key.publicKey());
            verifier.update((segments[0] + "." + segments[1])
                    .getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(signatureBytes)) {
                throw denied("WORKLOAD_PROOF_SIGNATURE_INVALID");
            }
        } catch (GeneralSecurityException exception) {
            throw denied("WORKLOAD_PROOF_SIGNATURE_INVALID");
        }
        return new ProofParts(
                strictObject(mapper, claimBytes, CLAIM_FIELDS, "WORKLOAD_CLAIMS_INVALID"),
                keyId, key);
    }

    private static ProofClaims parseClaims(JsonNode claims, String keyId) {
        WorkloadAuthentication authentication;
        HrisHomeContributionMaterializationV1.OwnerPopulationScope scope;
        HrisHomeContributionMaterializationV1.BrowserAudience audience;
        try {
            authentication = WorkloadAuthentication.valueOf(requiredText(claims, "authn"));
            scope = HrisHomeContributionMaterializationV1.OwnerPopulationScope.valueOf(
                    requiredText(claims, "ownerPopulationScope"));
            audience = HrisHomeContributionMaterializationV1.BrowserAudience.valueOf(
                    requiredText(claims, "browserAudience"));
            return new ProofClaims(
                    requiredText(claims, "iss"), requiredText(claims, "aud"),
                    requiredText(claims, "sub"),
                    Instant.ofEpochSecond(requiredLong(claims, "iat")),
                    Instant.ofEpochSecond(requiredLong(claims, "nbf")),
                    Instant.ofEpochSecond(requiredLong(claims, "exp")),
                    canonicalUuid(requiredText(claims, "jti")),
                    canonicalUuid(requiredText(claims, "requestNonce")),
                    requiredText(claims, "purpose"), requiredText(claims, "method"),
                    requiredText(claims, "path"), requiredLong(claims, "tenantId"),
                    canonicalUuid(requiredText(claims, "subjectPrincipalPublicId")),
                    canonicalUuid(requiredText(claims, "correlationId")),
                    requiredText(claims, "capability"), scope,
                    requiredLong(claims, "scopeRevision"),
                    requiredText(claims, "populationScopeDigest"),
                    requiredLong(claims, "effectivePermissionRevision"),
                    requiredLong(claims, "fieldPolicyRevision"),
                    requiredText(claims, "widgetKey"), audience, authentication, keyId);
        } catch (RuntimeException exception) {
            if (exception instanceof AdmissionDenied denied) throw denied;
            throw denied("WORKLOAD_CLAIMS_INVALID");
        }
    }

    private static void requireExactClaims(
            Endpoint endpoint,
            String exactPath,
            ProofClaims claims,
            Instant now,
            VerifiedSigningKey key) {
        if (!CALLER_WORKLOAD_ID.equals(claims.issuer())
                || !CALLER_WORKLOAD_ID.equals(claims.subject())
                || !endpoint.ownerAudience().equals(claims.audience())
                || !PURPOSE.equals(claims.purpose())
                || !METHOD.equals(claims.method())
                || !exactPath.equals(claims.path())
                || claims.tenantId() < 1
                || claims.scopeRevision() < 1
                || claims.effectivePermissionRevision() < 1
                || claims.fieldPolicyRevision() < 1
                || !DIGEST.matcher(claims.populationScopeDigest()).matches()
                || !CAPABILITY.matcher(claims.capability()).matches()
                || !endpoint.admitsContext(claims)) {
            throw denied("EXACT_HOME_MATERIALIZATION_CLAIMS_REQUIRED");
        }
        if (!claims.issuedAt().equals(claims.notBefore())
                || claims.proofId().equals(claims.requestNonce())
                || claims.issuedAt().isAfter(now)
                || !claims.expiresAt().isAfter(now)
                || !claims.expiresAt().isAfter(claims.issuedAt())
                || claims.expiresAt().getEpochSecond() - claims.issuedAt().getEpochSecond() > 30) {
            throw denied("FRESH_NON_REPLAYABLE_HOME_PROOF_REQUIRED");
        }
        if (!CALLER_WORKLOAD_ID.equals(key.workloadId())
                || key.authentication() != claims.authentication()) {
            throw denied("TRUST_DERIVED_WORKLOAD_IDENTITY_MISMATCH");
        }
        if (claims.authentication() == WorkloadAuthentication.MTLS_SPIFFE
                && !claims.subject().equals(key.workloadId())) {
            throw denied("SPIFFE_SUBJECT_MISMATCH");
        }
    }

    private static JsonNode strictObject(
            ObjectMapper mapper, byte[] bytes, Set<String> fields, String reason) {
        try (JsonParser parser = mapper.getFactory().createParser(bytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = mapper.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) {
                throw denied(reason);
            }
            Set<String> actual = new HashSet<>();
            node.fieldNames().forEachRemaining(actual::add);
            if (!actual.equals(fields)) throw denied(reason);
            return node;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof AdmissionDenied denied) throw denied;
            throw denied(reason);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw denied("WORKLOAD_CLAIMS_INVALID");
        }
        return value.textValue();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw denied("WORKLOAD_CLAIMS_INVALID");
        }
        return value.longValue();
    }

    private static UUID canonicalUuid(String raw) {
        try {
            UUID value = UUID.fromString(raw);
            if (!value.toString().equals(raw)) throw denied("CANONICAL_PROOF_UUID_REQUIRED");
            return value;
        } catch (IllegalArgumentException exception) {
            throw denied("CANONICAL_PROOF_UUID_REQUIRED");
        }
    }

    private static byte[] decodeCanonicalBase64Url(String segment) {
        if (segment.indexOf('=') >= 0) throw denied("CANONICAL_COMPACT_JWS_REQUIRED");
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(segment);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(segment)) {
                throw denied("CANONICAL_COMPACT_JWS_REQUIRED");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw denied("CANONICAL_COMPACT_JWS_REQUIRED");
        }
    }

    /** Convenience only; GET materialization has no body or Content-Type requirement. */
    public static List<Header> headers(String compactProof) {
        List<Header> result = new ArrayList<>();
        result.add(new Header(SERVICE_IDENTITY_HEADER, CALLER_WORKLOAD_ID));
        result.add(new Header(WORKLOAD_PROOF_HEADER, compactProof));
        result.add(new Header("Accept", "application/json"));
        return List.copyOf(result);
    }

    public static final class AdmissionDenied extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String reason;

        private AdmissionDenied(String reason) {
            super(reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    private static AdmissionDenied denied(String reason) {
        return new AdmissionDenied(reason);
    }

    private record ProofParts(JsonNode claims, String keyId, VerifiedSigningKey key) {
    }
}
