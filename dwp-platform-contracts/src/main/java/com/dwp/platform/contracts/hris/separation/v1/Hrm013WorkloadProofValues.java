package com.dwp.platform.contracts.hris.separation.v1;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import java.util.function.Function;

import static com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofViews.*;

/** Scalar proof validation, digests and failure construction. */
final class Hrm013WorkloadProofValues {

    static final int MAXIMUM_JOSE_BYTES = 1_024;

    static final int MAXIMUM_CLAIMS_BYTES = 14_336;

    static final int MAXIMUM_SIGNATURE_BYTES = 1_024;

    static final long MAXIMUM_SAFE_JSON_INTEGER = 9_007_199_254_740_991L;

    static final UUID TERMINAL_CHILD_NAMESPACE =
            UUID.fromString("8ca8c32b-c8f7-5ab4-a9a5-18166dc32128");

    static final Set<String> EXACT_HEADER_NAMES = Set.of(
            "content-type", "accept", "x-dwp-service-identity",
            "x-dwp-workload-proof", "x-correlation-id");

    static final Set<String> JOSE_FIELDS = Set.of("alg", "typ", "kid");

    static final Set<String> COMMON_CLAIM_FIELDS = Set.of(
            "iss", "aud", "sub", "iat", "nbf", "exp", "jti", "requestNonce",
            "bindingId", "purpose", "method", "path", "tenantId", "correlationId",
            "requestBodySha256", "authn", "cnf");

    static final Set<String> BUSINESS_AUTHORITY_FIELDS = Set.of(
            "tenantGrantRevision", "authorizationRevision", "grantUseMode");

    static final Set<String> RESOLVER_AUTHORITY_FIELDS = Set.of(
            "resolutionMode", "signedTenantGrantRevision", "signedAuthorizationRevision");

    static final Set<String> DRAIN_FIELDS = Set.of(
            "drainRootWorkItemId", "drainRootCreatedAt", "drainRootPayloadSha256",
            "drainContinuationKind", "drainContinuationEventId",
            "drainContinuationRevision", "drainContinuationPayloadSha256");

    static final Set<String> RESERVED_CLAIM_FIELDS;

    static final Pattern KEY_ID =
            Pattern.compile("^[A-Za-z0-9_-][A-Za-z0-9._:-]{0,127}$");

    static final Pattern SERVICE_ID =
            Pattern.compile("^dwp-[a-z][a-z0-9-]{1,62}$");

    static final Pattern BINDING_ID =
            Pattern.compile("^SVC-[A-Z]{3}-[A-Z]{3}-[0-9]{3}$");

    static final Pattern PURPOSE =
            Pattern.compile("^[A-Z][A-Z0-9_]{2,119}$");

    static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    static final Pattern HEADER_NAME =
            Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");

    static final Pattern PROFILE_CLAIM_NAME =
            Pattern.compile("^[a-z][A-Za-z0-9]{1,79}$");

    static {
        Set<String> reserved = new HashSet<>(COMMON_CLAIM_FIELDS);
        reserved.addAll(BUSINESS_AUTHORITY_FIELDS);
        reserved.addAll(RESOLVER_AUTHORITY_FIELDS);
        reserved.addAll(DRAIN_FIELDS);
        RESERVED_CLAIM_FIELDS = Set.copyOf(reserved);
    }

    static final int MAXIMUM_JWS_BYTES = 16_384;

    static final int MAXIMUM_SINGLE_APPLICATION_HEADER_BYTES = 16_384;

    static final int MAXIMUM_AGGREGATE_APPLICATION_HEADER_BYTES = 32_768;

    static final long MAXIMUM_PROOF_LIFETIME_SECONDS = 30;

    static final long MAXIMUM_CLOCK_SKEW_SECONDS = 5;

    static final int MINIMUM_RSA_BITS = 2_048;

    static final String CONTENT_TYPE_HEADER = "Content-Type";

    static final String ACCEPT_HEADER = "Accept";

    static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";

    static final String WORKLOAD_PROOF_HEADER = "X-DWP-Workload-Proof";

    static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    static final String JSON_MEDIA_TYPE = "application/json";

    private final Function<String, RuntimeException> failures;
    private final java.util.function.Predicate<RuntimeException> controlledFailure;

    Hrm013WorkloadProofValues(
            Function<String, RuntimeException> failures,
            java.util.function.Predicate<RuntimeException> controlledFailure) {
        this.failures = failures;
        this.controlledFailure = controlledFailure;
    }

    void requireExactFields(
            JsonNode object, Set<String> expected, Object code) {
        Set<String> actual = new HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw failure(code);
        }
    }

    String requiredText(JsonNode node, String field, Object code) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()
                || !value.textValue().equals(value.textValue().trim())
                || hasControl(value.textValue())) {
            throw failure(code);
        }
        return value.textValue();
    }

    long positiveLong(JsonNode node, String field, Object code) {
        long value = exactLong(node, field, code);
        if (value < 1 || value > MAXIMUM_SAFE_JSON_INTEGER) {
            throw failure(code);
        }
        return value;
    }

    long nonNegativeLong(JsonNode node, String field, Object code) {
        long value = exactLong(node, field, code);
        if (value < 0 || value > MAXIMUM_SAFE_JSON_INTEGER) {
            throw failure(code);
        }
        return value;
    }

    long exactLong(JsonNode node, String field, Object code) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw failure(code);
        }
        return value.longValue();
    }

    Instant epochSecond(JsonNode node, String field) {
        long value = nonNegativeLong(node, field, "CLAIMS_INVALID");
        try {
            return Instant.ofEpochSecond(value);
        } catch (RuntimeException exception) {
            throw failure("CLAIMS_INVALID");
        }
    }

    String requiredDigest(JsonNode node, String field) {
        String value = requiredText(node, field, "CLAIMS_INVALID");
        if (!SHA256.matcher(value).matches()) {
            throw failure("CLAIMS_INVALID");
        }
        return value;
    }

    UUID canonicalUuid(String raw, Object code) {
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

    Instant canonicalInstant(String raw, Object code) {
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

    <E extends Enum<E>> E enumValue(
            Class<E> type, String raw, Object code) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException exception) {
            throw failure(code);
        }
    }

    byte[] decodeCanonicalBase64Url(
            String raw, int maximumDecodedBytes, Object code) {
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

    String boundedIdentifier(String value) {
        if (value.length() > 120
                || !Pattern.matches("^[A-Za-z][A-Za-z0-9._:-]{1,119}$", value)) {
            throw failure("PROFILE_CLAIMS_INVALID");
        }
        return value;
    }

    String boundedCode(String value) {
        if (value.length() > 120 || !PURPOSE.matcher(value).matches()) {
            throw failure("PROFILE_CLAIMS_INVALID");
        }
        return value;
    }

    String boundedText(String value) {
        if (value.length() > 512 || value.isBlank() || hasControl(value)) {
            throw failure("PROFILE_CLAIMS_INVALID");
        }
        return value;
    }

    void requirePath(String path) {
        if (path == null || path.length() > 512 || !path.startsWith("/internal/")
                || path.indexOf('?') >= 0 || path.indexOf('#') >= 0
                || path.endsWith("/") || hasControl(path)) {
            throw new IllegalArgumentException("path is not an exact internal path");
        }
    }

    void requireServiceId(String value, String field) {
        if (value == null || !SERVICE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a canonical service ID");
        }
    }

    void requireBindingId(String value) {
        if (value == null || !BINDING_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("bindingId is not canonical");
        }
    }

    void requirePurpose(String value) {
        if (value == null || !PURPOSE.matcher(value).matches()) {
            throw new IllegalArgumentException("purpose is not canonical");
        }
    }

    void requireDigest(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a lowercase SHA-256 digest");
        }
    }

    void requireThumbprint(String value, String field) {
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

    boolean hasLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    boolean hasControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    UUID uuidV5(UUID namespace, byte[] name) {
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

    byte[] uuidBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    String sha256(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Deterministic terminal-child identity from the frozen HRM-013 UUIDv5 rule. */
    UUID terminalChildEventId(
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

    String commandDigest(
            ProfileView profile, long tenantId, String requestBodySha256) {
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

    void requireFreshness(
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
            throw failure("FRESHNESS_INVALID");
        }
        if (!issuedAt.equals(notBefore)
                || proofId.equals(requestNonce)
                || lifetime < 1 || lifetime > MAXIMUM_PROOF_LIFETIME_SECONDS
                || issuedAt.isAfter(now.plusSeconds(MAXIMUM_CLOCK_SKEW_SECONDS))
                || !expiresAt.isAfter(now.minusSeconds(MAXIMUM_CLOCK_SKEW_SECONDS))) {
            throw failure("FRESHNESS_INVALID");
        }
    }

    String parseConfirmation(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw failure("CLAIMS_INVALID");
        }
        requireExactFields(node, Set.of("x5t#S256"), "CLAIMS_INVALID");
        String thumbprint = requiredText(node, "x5t#S256", "CLAIMS_INVALID");
        try {
            requireThumbprint(thumbprint, "cnf.x5t#S256");
            return thumbprint;
        } catch (IllegalArgumentException exception) {
            throw failure("CLAIMS_INVALID");
        }
    }

    RuntimeException failure(Object code) {
        return failures.apply(code.toString());
    }

    boolean isFailure(RuntimeException exception) {
        return controlledFailure.test(exception);
    }

    void requireProfile(
            String bindingId,
            String callerWorkloadId,
            String ownerWorkloadId,
            String purpose,
            String exactPath,
            int maximumBodyBytes,
            Map<String, ? extends Enum<?>> profileClaimTypes,
            Map<String, Object> expectedProfileClaims,
            Set<? extends Enum<?>> allowedDrainKinds) {
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
        for (Map.Entry<String, ? extends Enum<?>> entry : profileClaimTypes.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || !PROFILE_CLAIM_NAME.matcher(entry.getKey()).matches()
                    || RESERVED_CLAIM_FIELDS.contains(entry.getKey())) {
                throw new IllegalArgumentException("invalid or reserved profile claim");
            }
        }
        if (!profileClaimTypes.keySet().containsAll(expectedProfileClaims.keySet())) {
            throw new IllegalArgumentException("expected claim is not declared by profile");
        }
    }
}
