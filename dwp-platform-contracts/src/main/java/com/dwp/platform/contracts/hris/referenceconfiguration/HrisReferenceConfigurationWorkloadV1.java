package com.dwp.platform.contracts.hris.referenceconfiguration;

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


/** Dedicated reference-configuration proof profile; independent of approval bridge endpoints. */
public final class HrisReferenceConfigurationWorkloadV1 {

    public static final String WORKLOAD_PROOF_HEADER = "X-DWP-Workload-Proof";
    public static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    private static final Set<String> JOSE_FIELDS = Set.of("alg", "typ", "kid");
    private static final Set<String> CLAIM_FIELDS = Set.of(
            "iss", "aud", "sub", "iat", "nbf", "exp", "jti", "requestNonce",
            "bindingId", "purpose", "method", "path", "tenantId",
            "requestBodySha256", "authn");
    private static final Set<String> PROHIBITED_HEADERS = Set.of(
            "authorization", "cookie", "origin", "referer", "x-dwp-user-id",
            "x-dwp-person-public-id", "x-dwp-display-name-b64",
            "x-forwarded-user", "x-forwarded-email", "x-roles", "x-permissions");
    private static final Set<String> RECOGNIZED_DWP_HEADERS = Set.of(
            "x-dwp-workload-proof", "x-dwp-service-identity");
    private static final Pattern KID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$");

    private HrisReferenceConfigurationWorkloadV1() {
    }


    public enum Endpoint {
        PREPARE("HRIS_PERFORMANCE_ARTIFACT_PREPARE_V1", "dwp-people-server", "dwp-platform-server",
                "/internal/hris-performance/v1/configuration-artifacts:prepare", "OWNER_ARTIFACT_PREPARE"),
        FINALIZE("HRIS_PERFORMANCE_ARTIFACT_FINALIZE_V1", "dwp-people-server", "dwp-platform-server",
                "/internal/hris-performance/v1/configuration-artifacts/{artifactPublicId}:finalize", "OWNER_ARTIFACT_FINALIZE"),
        VALIDATE("HRIS_PERFORMANCE_ARTIFACT_VALIDATE_V1", "dwp-people-server", "dwp-platform-server",
                "/internal/hris-performance/v1/configuration-artifacts/{artifactPublicId}/validation-receipts", "OWNER_ARTIFACT_VALIDATE"),
        SIMULATE("HRIS_PERFORMANCE_ARTIFACT_SIMULATE_V1", "dwp-people-server", "dwp-platform-server",
                "/internal/hris-performance/v1/configuration-artifacts/{artifactPublicId}/simulation-receipts", "OWNER_ARTIFACT_SIMULATE"),
        PROJECTION("HRIS_REFERENCE_CONFIGURATION_OWNER_PROJECTION_V1", "dwp-platform-server", "dwp-people-server",
                "/internal/hris-reference-configuration/v1/owner-projections:query", "OWNER_LIFECYCLE_PROJECTION"),
        CREATE_LOOKUP("HRIS_REFERENCE_CONFIGURATION_AUTHORITATIVE_CREATE_LOOKUP_V1", "dwp-platform-server", "dwp-people-server",
                "/internal/hris-reference-configuration/v1/authoritative-create-lookups:query", "AUTHORITATIVE_CREATE_LOOKUP"),
        FEATURE("HRIS_PERFORMANCE_FEATURE_DECISION_V1", "dwp-auth-server", "dwp-platform-server",
                "/internal/auth/v1/hris-performance-feature-decisions:query", "FEATURE_POLICY_LOOKUP_ONLY");

        private final String bindingId, ownerWorkloadId, callerWorkloadId, template, purpose;
        Endpoint(String bindingId, String owner, String caller, String template, String purpose) {
            this.bindingId = bindingId; this.ownerWorkloadId = owner; this.callerWorkloadId = caller;
            this.template = template; this.purpose = purpose;
        }
        public String bindingId() { return bindingId; }
        public String ownerWorkloadId() { return ownerWorkloadId; }
        public String callerWorkloadId() { return callerWorkloadId; }
        public String purpose() { return purpose; }
        public String method() { return "POST"; }
        public String path(UUID artifactPublicId) {
            return template.contains("{artifactPublicId}")
                    ? template.replace("{artifactPublicId}", Objects.requireNonNull(artifactPublicId).toString()) : template;
        }
        public boolean matchesPath(String path) {
            if (path == null) return false;
            if (!template.contains("{artifactPublicId}")) return template.equals(path);
            int start = template.indexOf("{artifactPublicId}");
            String prefix = template.substring(0, start), suffix = template.substring(start + 18);
            if (!path.startsWith(prefix) || !path.endsWith(suffix)) return false;
            try {
                String raw = path.substring(prefix.length(), path.length() - suffix.length());
                return UUID.fromString(raw).toString().equals(raw) && path(UUID.fromString(raw)).equals(path);
            } catch (IllegalArgumentException | IndexOutOfBoundsException exception) { return false; }
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }
    public static final class AdmissionFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private AdmissionFailure(String code) { super(code); }
    }
    private static AdmissionFailure denied(String code) { return new AdmissionFailure(code); }

    public enum WorkloadAuthentication {
        MTLS_SPIFFE,
        CLIENT_CREDENTIAL_JWT
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

    /**
     * The adapter resolves the proof key from its real trust store. The returned
     * authentication and workload identity are derived from the mTLS certificate
     * or registered client-key profile, never copied from the proof claim.
     */
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

    /** Owner-side current grant and target-tenant revalidation port. */
    @FunctionalInterface
    public interface TenantAdmissionVerifier {
        boolean admits(
                Endpoint endpoint,
                String exactPath,
                String callerWorkloadId,
                long signedTenantId);
    }

    /** A durable implementation reserves jti and nonce in one transaction. */
    @FunctionalInterface
    public interface ReplayStore {
        boolean reserve(UUID proofId, UUID requestNonce, Instant expiresAt);

        /**
         * Production stores also need the signed tenant and fixed binding when
         * writing their durable evidence row. The compatibility default keeps
         * the small in-memory fixture contract unchanged.
         */
        default boolean reserve(Endpoint endpoint, ProofClaims claims) {
            return reserve(claims.proofId(), claims.requestNonce(), claims.expiresAt());
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
            String requestBodySha256,
            WorkloadAuthentication authentication,
            String keyId) {
    }

    public record VerifiedAdmission(ProofClaims claims, String commandDigest) {
        public long tenantId() {
            return claims.tenantId();
        }
    }

    public static VerifiedAdmission verify(
            ObjectMapper mapper,
            Clock clock,
            Endpoint endpoint,
            String exactPath,
            List<Header> requestHeaders,
            byte[] exactBodyBytes,
            SigningKeyResolver keyResolver,
            TenantAdmissionVerifier tenantVerifier,
            ReplayStore replayStore) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(exactPath, "exactPath");
        Objects.requireNonNull(exactBodyBytes, "exactBodyBytes");
        Objects.requireNonNull(keyResolver, "keyResolver");
        Objects.requireNonNull(tenantVerifier, "tenantVerifier");
        Objects.requireNonNull(replayStore, "replayStore");
        if (!endpoint.matchesPath(exactPath)
                || exactPath.indexOf('?') >= 0 || exactPath.indexOf('#') >= 0) {
            throw denied("EXACT_INTERNAL_PATH_REQUIRED");
        }

        Map<String, String> headers = validateHeaders(requestHeaders);
        if (!endpoint.callerWorkloadId().equals(headers.get("x-dwp-service-identity"))) {
            throw denied("CALLER_SERVICE_IDENTITY_MISMATCH");
        }
        String compact = headers.get("x-dwp-workload-proof");
        ProofParts parts = parseAndVerifyProof(mapper, compact, keyResolver);
        ProofClaims claims = parseClaims(parts.claims(), parts.keyId());
        String bodySha256 = sha256(exactBodyBytes);
        requireExactClaims(endpoint, exactPath, bodySha256, claims, clock.instant(), parts.key());
        if (!tenantVerifier.admits(
                endpoint, exactPath, endpoint.callerWorkloadId(), claims.tenantId())) {
            throw denied("CURRENT_TENANT_AUTHORITY_REQUIRED");
        }
        if (!replayStore.reserve(endpoint, claims)) {
            throw denied("WORKLOAD_PROOF_REPLAYED");
        }
        return new VerifiedAdmission(claims, sha256((endpoint.bindingId() + "\n" + exactPath + "\n" + claims.tenantId() + "\n" + bodySha256).getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, String> validateHeaders(List<Header> requestHeaders) {
        if (requestHeaders == null) {
            throw denied("REQUEST_HEADER_EVIDENCE_REQUIRED");
        }
        Map<String, String> normalized = new HashMap<>();
        for (Header header : requestHeaders) {
            if (header == null) {
                throw denied("DUPLICATE_OR_INVALID_HEADER_EVIDENCE");
            }
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
        String contentType = normalized.get("content-type");
        if (!isJsonContentType(contentType)) {
            throw denied("EXACT_JSON_CONTENT_TYPE_REQUIRED");
        }
        String accept = normalized.get("accept");
        if (accept != null && !"application/json".equalsIgnoreCase(accept.trim())) {
            throw denied("JSON_ACCEPT_REQUIRED");
        }
        if (normalized.get("x-dwp-service-identity") == null
                || normalized.get("x-dwp-workload-proof") == null) {
            throw denied("WORKLOAD_HEADERS_REQUIRED");
        }
        return Map.copyOf(normalized);
    }

    private static boolean isJsonContentType(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        return "application/json".equals(normalized)
                || "application/json; charset=utf-8".equals(normalized)
                || "application/json;charset=utf-8".equals(normalized);
    }

    private static boolean isProhibited(String name) {
        return PROHIBITED_HEADERS.contains(name)
                || name.startsWith("sec-fetch-")
                || name.startsWith("sec-ch-ua")
                || name.startsWith("x-user-") || name.startsWith("x-tenant-")
                || name.startsWith("x-subject-") || name.startsWith("x-person-")
                || name.startsWith("x-context-") || name.startsWith("x-scope-")
                || name.startsWith("x-field-") || name.startsWith("x-population-")
                || name.startsWith("x-current-") || name.startsWith("x-authorization-")
                || name.startsWith("x-expected-") || name.startsWith("x-purpose-")
                || name.startsWith("x-dwp-tenant");
    }

    private static ProofParts parseAndVerifyProof(
            ObjectMapper mapper,
            String compact,
            SigningKeyResolver resolver) {
        if (compact == null || compact.length() > 8192) throw denied("BOUNDED_WORKLOAD_PROOF_REQUIRED");
        String[] segments = compact.split("\\.", -1);
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
        if (!KID.matcher(keyId).matches()) {
            throw denied("JOSE_KEY_ID_INVALID");
        }
        VerifiedSigningKey key;
        try {
            key = resolver.resolve(keyId);
        } catch (RuntimeException exception) {
            throw denied("WORKLOAD_SIGNING_KEY_UNTRUSTED");
        }
        if (key == null) {
            throw denied("WORKLOAD_SIGNING_KEY_UNTRUSTED");
        }
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
        JsonNode claims = strictObject(mapper, claimBytes, CLAIM_FIELDS, "WORKLOAD_CLAIMS_INVALID");
        return new ProofParts(claims, keyId, key);
    }

    private static ProofClaims parseClaims(JsonNode claims, String keyId) {
        long iat = requiredLong(claims, "iat");
        long nbf = requiredLong(claims, "nbf");
        long exp = requiredLong(claims, "exp");
        long tenantId = requiredLong(claims, "tenantId");
        UUID proofId = canonicalUuid(requiredText(claims, "jti"));
        UUID requestNonce = canonicalUuid(requiredText(claims, "requestNonce"));
        WorkloadAuthentication authentication;
        try {
            authentication = WorkloadAuthentication.valueOf(requiredText(claims, "authn"));
        } catch (IllegalArgumentException exception) {
            throw denied("WORKLOAD_AUTHENTICATION_PROFILE_INVALID");
        }
        try {
            return new ProofClaims(
                    requiredText(claims, "iss"), requiredText(claims, "aud"),
                    requiredText(claims, "sub"), Instant.ofEpochSecond(iat),
                    Instant.ofEpochSecond(nbf), Instant.ofEpochSecond(exp),
                    proofId, requestNonce, requiredText(claims, "bindingId"),
                    requiredText(claims, "purpose"), requiredText(claims, "method"),
                    requiredText(claims, "path"), tenantId,
                    requiredText(claims, "requestBodySha256"), authentication, keyId);
        } catch (RuntimeException exception) {
            if (exception instanceof AdmissionFailure failure) {
                throw failure;
            }
            throw denied("WORKLOAD_CLAIMS_INVALID");
        }
    }

    private static void requireExactClaims(
            Endpoint endpoint,
            String exactPath,
            String bodySha256,
            ProofClaims claims,
            Instant now,
            VerifiedSigningKey key) {
        if (!endpoint.callerWorkloadId().equals(claims.issuer())
                || !endpoint.callerWorkloadId().equals(claims.subject())
                || !endpoint.ownerWorkloadId().equals(claims.audience())
                || !endpoint.bindingId().equals(claims.bindingId())
                || !endpoint.purpose().equals(claims.purpose())
                || !endpoint.method().equals(claims.method())
                || !exactPath.equals(claims.path())
                || claims.path().indexOf('?') >= 0
                || claims.tenantId() < 1
                || !bodySha256.equals(claims.requestBodySha256())) {
            throw denied("EXACT_WORKLOAD_CLAIMS_REQUIRED");
        }
        if (!claims.issuedAt().equals(claims.notBefore())
                || claims.proofId().equals(claims.requestNonce())
                || claims.issuedAt().isAfter(now)
                || !claims.expiresAt().isAfter(now)
                || !claims.expiresAt().isAfter(claims.issuedAt())
                || claims.expiresAt().getEpochSecond() - claims.issuedAt().getEpochSecond() > 30) {
            throw denied("FRESH_NON_REPLAYABLE_PROOF_REQUIRED");
        }
        if (key.authentication() != claims.authentication()
                || !endpoint.callerWorkloadId().equals(key.workloadId())) {
            throw denied("DERIVED_WORKLOAD_AUTHENTICATION_MISMATCH");
        }
        if (claims.authentication() == WorkloadAuthentication.MTLS_SPIFFE
                && !claims.subject().equals(key.workloadId())) {
            throw denied("SPIFFE_SUBJECT_MISMATCH");
        }
    }

    private static JsonNode strictObject(
            ObjectMapper mapper,
            byte[] bytes,
            Set<String> exactFields,
            String error) {
        try (JsonParser parser = mapper.getFactory().createParser(bytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = mapper.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) {
                throw denied(error);
            }
            Set<String> actual = new HashSet<>();
            node.fieldNames().forEachRemaining(actual::add);
            if (!actual.equals(exactFields)) {
                throw denied(error);
            }
            return node;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof AdmissionFailure failure) {
                throw failure;
            }
            throw denied(error);
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
            if (!value.toString().equals(raw)) {
                throw denied("CANONICAL_PROOF_UUID_REQUIRED");
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw denied("CANONICAL_PROOF_UUID_REQUIRED");
        }
    }

    private static byte[] decodeCanonicalBase64Url(String segment) {
        if (segment.indexOf('=') >= 0) {
            throw denied("CANONICAL_COMPACT_JWS_REQUIRED");
        }
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

    /** Useful for adapters that preserve the original header occurrence order. */
    public static List<Header> headers(String serviceIdentity, String compactProof) {
        List<Header> result = new ArrayList<>();
        result.add(new Header("Content-Type", "application/json"));
        result.add(new Header(SERVICE_IDENTITY_HEADER, serviceIdentity));
        result.add(new Header(WORKLOAD_PROOF_HEADER, compactProof));
        result.add(new Header("Accept", "application/json"));
        return List.copyOf(result);
    }

    private record ProofParts(
            JsonNode claims,
            String keyId,
            VerifiedSigningKey key) {
    }
}
