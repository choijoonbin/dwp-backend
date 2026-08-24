package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeContract;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Verifies Auth-signed, command-bound RS256 step-up challenges without trusting client mode headers. */
@Component
public final class ApprovalStepUpVerifier {

    private static final String ALGORITHM = "RS256";
    private static final String TOKEN_TYPE = "JWT";
    private static final String OWNER_SERVICE_KEY = "approval";
    private static final int MAXIMUM_TOKEN_LENGTH = 16_384;
    private static final int MAXIMUM_HEADER_SEGMENT_LENGTH = 2_048;
    private static final int MAXIMUM_CLAIMS_SEGMENT_LENGTH = 12_288;
    private static final int MAXIMUM_SIGNATURE_SEGMENT_LENGTH = 2_048;
    private static final int MAXIMUM_JSON_DEPTH = 6;
    private static final int MAXIMUM_JSON_FIELDS = 64;
    private static final Set<String> HEADER_FIELDS =
            ProductSurfaceStepUpChallengeContract.HEADER_FIELDS;
    private static final Set<String> CLAIM_FIELDS =
            ProductSurfaceStepUpChallengeContract.CLAIM_FIELDS;
    private final ObjectMapper objectMapper;
    private final ObjectMapper tokenObjectMapper;
    private final Clock clock;
    private final PublicKey publicKey;
    private final String issuer;
    private final String audience;
    private final String keyId;
    private final String requiredAcr;
    private final long maximumAuthenticationAgeSeconds;
    private final long maximumChallengeTtlSeconds;

    @Autowired
    public ApprovalStepUpVerifier(
            ObjectMapper objectMapper,
            @Value("${dwp.approval.step-up.public-key-pem:}") String publicKeyPem,
            @Value("${dwp.approval.step-up.issuer:}") String issuer,
            @Value("${dwp.approval.step-up.audience:dwp-approval-server}") String audience,
            @Value("${dwp.approval.step-up.key-id:}") String keyId,
            @Value("${dwp.approval.step-up.required-acr:urn:dwp:acr:mfa}") String requiredAcr,
            @Value("${dwp.approval.step-up.maximum-authentication-age-seconds:600}")
            long maximumAuthenticationAgeSeconds,
            @Value("${dwp.approval.step-up.maximum-challenge-ttl-seconds:900}")
            long maximumChallengeTtlSeconds) {
        this(objectMapper, Clock.systemUTC(), parseKey(publicKeyPem), issuer, audience, keyId,
                requiredAcr, maximumAuthenticationAgeSeconds, maximumChallengeTtlSeconds);
    }

    ApprovalStepUpVerifier(
            ObjectMapper objectMapper,
            Clock clock,
            PublicKey publicKey,
            String issuer,
            String audience,
            String keyId,
            String requiredAcr,
            long maximumAuthenticationAgeSeconds,
            long maximumChallengeTtlSeconds) {
        if (maximumAuthenticationAgeSeconds <= 0 || maximumChallengeTtlSeconds <= 0) {
            throw new IllegalArgumentException("Step-up assurance lifetimes must be positive.");
        }
        this.objectMapper = objectMapper;
        this.tokenObjectMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.clock = clock;
        this.publicKey = publicKey;
        this.issuer = normalized(issuer);
        this.audience = normalized(audience);
        this.keyId = normalized(keyId);
        this.requiredAcr = normalized(requiredAcr);
        this.maximumAuthenticationAgeSeconds = maximumAuthenticationAgeSeconds;
        this.maximumChallengeTtlSeconds = maximumChallengeTtlSeconds;
    }

    public VerifiedChallenge verify(String compactToken, CommandBinding expected) {
        requireAvailable();
        if (compactToken == null || compactToken.isBlank()) {
            throw stepUpRequired("Step-up challenge is required.");
        }
        String token = compactToken.trim();
        if (token.length() > MAXIMUM_TOKEN_LENGTH) {
            throw mismatch("Step-up challenge is too large.");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3
                || !validSegment(parts[0], MAXIMUM_HEADER_SEGMENT_LENGTH)
                || !validSegment(parts[1], MAXIMUM_CLAIMS_SEGMENT_LENGTH)
                || !validSegment(parts[2], MAXIMUM_SIGNATURE_SEGMENT_LENGTH)) {
            throw mismatch("Step-up challenge format is invalid.");
        }
        JsonNode header = decodeJson(parts[0]);
        requireExactFields(header, HEADER_FIELDS, "header");
        if (!ALGORITHM.equals(text(header, "alg"))
                || !TOKEN_TYPE.equals(text(header, "typ"))
                || !keyId.equals(text(header, "kid"))) {
            throw mismatch("Step-up challenge signing metadata does not match policy.");
        }
        verifySignature(parts);
        JsonNode claims = decodeJson(parts[1]);
        requireAssurancePresence(claims);
        requireExactFields(claims, CLAIM_FIELDS, "claims");
        Instant now = clock.instant();
        long issuedAt = requiredEpoch(claims, "iat");
        long expiresAt = requiredEpoch(claims, "exp");
        long notBefore = requiredEpoch(claims, "nbf");
        long authenticatedAt = requiredAssuranceEpoch(claims, "auth_time");
        if (expiresAt <= issuedAt
                || notBefore < issuedAt - 30
                || notBefore >= expiresAt
                || authenticatedAt > issuedAt + 30
                || notBefore > now.plusSeconds(30).getEpochSecond()
                || issuedAt > now.plusSeconds(30).getEpochSecond()
                || expiresAt - issuedAt > maximumChallengeTtlSeconds) {
            throw mismatch("Step-up challenge assurance window is invalid.");
        }
        if (expiresAt <= now.getEpochSecond()
                || authenticatedAt > now.plusSeconds(30).getEpochSecond()
                || now.getEpochSecond() - authenticatedAt > maximumAuthenticationAgeSeconds) {
            throw stepUpRequired("Fresh step-up authentication is required.");
        }
        requireClaim(claims, "iss", issuer);
        if (!audienceMatches(claims.get("aud"), audience)) {
            throw mismatch("Step-up audience mismatch.");
        }
        requireAssuranceClaim(claims, "acr", requiredAcr);
        if (!requiredAmr(claims.get("amr")).contains("mfa")) {
            throw stepUpRequired("Step-up MFA evidence is incomplete.");
        }
        requireClaim(claims, "sub", Long.toString(expected.actorUserId()));
        requireLongClaim(claims, "tenant_id", expected.tenantId());
        requireClaim(claims, "owner_service_key", OWNER_SERVICE_KEY);
        requireClaim(claims, "command_contract_key", expected.commandContractKey());
        requireClaim(claims, "activation_policy", expected.activationPolicy());
        requireClaim(claims, "capability_contract_key", expected.capabilityContractKey());
        requireClaim(claims, "context_key", expected.contextKey());
        requireClaim(claims, "scope_ref", expected.scopeRef());
        requireClaim(claims, "target_type", expected.targetType());
        requireClaim(claims, "target_id", expected.targetId());
        requireLongClaim(claims, "target_version", expected.targetVersion());
        requireClaim(claims, "command_method", expected.commandMethod());
        requireClaim(claims, "command_path", expected.commandPath());
        requireClaim(claims, "idempotency_key", expected.idempotencyKey());
        requireClaim(claims, "payload_sha256", expected.payloadSha256());
        requireClaim(claims, "command_sha256", commandSha256(expected));
        requireClaim(claims, "decision_revision", expected.decisionRevision());
        String challengeId = requiredText(claims, "jti", 160);
        String nonce = requiredText(claims, "nonce", 160);
        return new VerifiedChallenge(
                challengeId, nonce, issuer, expected, Instant.ofEpochSecond(expiresAt));
    }

    public String payloadSha256(Object payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(canonical(objectMapper.valueToTree(payload)))));
        } catch (JsonProcessingException | java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Step-up payload digest could not be computed.", exception);
        }
    }

    private void requireAvailable() {
        if (publicKey == null || issuer.isBlank() || audience.isBlank()
                || keyId.isBlank() || requiredAcr.isBlank()) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Step-up challenge verification is not configured.");
        }
    }

    private JsonNode decodeJson(String value) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            JsonNode result = tokenObjectMapper.readTree(decoded);
            if (!result.isObject()
                    || jsonDepth(result, 1) > MAXIMUM_JSON_DEPTH
                    || jsonFieldCount(result) > MAXIMUM_JSON_FIELDS) {
                throw mismatch("Step-up challenge JSON is invalid.");
            }
            return result;
        } catch (IllegalArgumentException | java.io.IOException exception) {
            throw mismatch("Step-up challenge encoding is invalid.");
        }
    }

    private boolean validSegment(String value, int maximumLength) {
        if (value.isEmpty() || value.length() > maximumLength) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'A' && character <= 'Z')
                    && !(character >= 'a' && character <= 'z')
                    && !(character >= '0' && character <= '9')
                    && character != '-' && character != '_') {
                return false;
            }
        }
        return true;
    }

    private void requireExactFields(JsonNode value, Set<String> expected, String part) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw mismatch("Step-up challenge " + part + " schema is invalid.");
        }
    }

    private void requireAssurancePresence(JsonNode claims) {
        if (!claims.has("auth_time") || !claims.has("acr") || !claims.has("amr")) {
            throw stepUpRequired("Step-up challenge assurance evidence is required.");
        }
    }

    private int jsonDepth(JsonNode value, int depth) {
        if (!value.isContainerNode()) return depth;
        int maximum = depth;
        for (JsonNode child : value) {
            maximum = Math.max(maximum, jsonDepth(child, depth + 1));
            if (maximum > MAXIMUM_JSON_DEPTH) return maximum;
        }
        return maximum;
    }

    private int jsonFieldCount(JsonNode value) {
        int count = value.isObject() ? value.size() : 0;
        for (JsonNode child : value) {
            count += jsonFieldCount(child);
            if (count > MAXIMUM_JSON_FIELDS) return count;
        }
        return count;
    }

    private void verifySignature(String[] parts) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw mismatch("Step-up challenge signature is invalid.");
            }
        } catch (BaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Step-up challenge signature verification is unavailable.", exception);
        }
    }

    private void requireClaim(JsonNode claims, String field, String expected) {
        if (!expected.equals(text(claims, field))) {
            throw mismatch("Step-up challenge " + field + " mismatch.");
        }
    }

    private void requireAssuranceClaim(JsonNode claims, String field, String expected) {
        if (!expected.equals(text(claims, field))) {
            throw stepUpRequired("Step-up " + field + " assurance is required.");
        }
    }

    private String text(JsonNode claims, String field) {
        JsonNode value = claims.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private void requireLongClaim(JsonNode claims, String field, long expected) {
        JsonNode value = claims.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToLong() || value.longValue() != expected) {
            throw mismatch("Step-up challenge " + field + " mismatch.");
        }
    }

    private long requiredEpoch(JsonNode claims, String field) {
        JsonNode value = claims.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw mismatch("Step-up challenge " + field + " is required.");
        }
        return value.longValue();
    }

    private long requiredAssuranceEpoch(JsonNode claims, String field) {
        JsonNode value = claims.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw stepUpRequired("Step-up challenge " + field + " is required.");
        }
        return value.longValue();
    }

    private String requiredText(JsonNode claims, String field, int maximum) {
        String raw = text(claims, field);
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank() || value.length() > maximum) {
            throw mismatch("Step-up challenge " + field + " is invalid.");
        }
        return value;
    }

    private boolean audienceMatches(JsonNode value, String expected) {
        if (value == null) return false;
        if (value.isTextual()) return expected.equals(value.textValue());
        return value.isArray() && value.size() == 1
                && value.get(0).isTextual()
                && expected.equals(value.get(0).textValue());
    }

    private Set<String> requiredAmr(JsonNode value) {
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > 8) {
            throw mismatch("Step-up challenge amr schema is invalid.");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        value.forEach(item -> {
            if (!item.isTextual() || item.textValue().isBlank()
                    || item.textValue().length() > 64 || !result.add(item.textValue())) {
                throw mismatch("Step-up challenge amr schema is invalid.");
            }
        });
        return Set.copyOf(result);
    }

    private String commandSha256(CommandBinding binding) {
        return ProductSurfaceStepUpChallengeContract.commandSha256(
                new ProductSurfaceStepUpChallengeContract.CommandMaterial(
                        binding.commandContractKey(), OWNER_SERVICE_KEY, audience,
                        binding.commandMethod(), binding.commandPath(), binding.contextKey(),
                        binding.scopeRef(), binding.targetType(), binding.targetId(),
                        binding.targetVersion(), binding.idempotencyKey(),
                        binding.payloadSha256(), binding.decisionRevision()));
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value.deepCopy();
    }

    private static PublicKey parseKey(String pem) {
        if (pem == null || pem.isBlank()) return null;
        try {
            String encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (Exception exception) {
            throw new IllegalStateException("Configured Approval step-up public key is invalid.", exception);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static BaseException mismatch(String message) {
        return new BaseException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH, message);
    }

    private static BaseException stepUpRequired(String message) {
        return new BaseException(ErrorCode.STEP_UP_REQUIRED, message);
    }

    public record CommandBinding(
            long actorUserId,
            long tenantId,
            String commandContractKey,
            String contextKey,
            String activationPolicy,
            String capabilityContractKey,
            String scopeRef,
            String targetType,
            String targetId,
            long targetVersion,
            String commandMethod,
            String commandPath,
            String idempotencyKey,
            String payloadSha256,
            String decisionRevision) {
    }

    public record VerifiedChallenge(
            String challengeId,
            String nonce,
            String issuer,
            CommandBinding binding,
            Instant expiresAt) {
    }
}
