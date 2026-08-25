package com.dwp.core.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

/**
 * Product-neutral verifier for Auth-signed, command-bound RS256 step-up
 * challenges. Owner services provide their exact owner/audience policy and
 * retain their own transactional replay ledger.
 */
public final class ProductSurfaceStepUpChallengeVerifier {

    private static final int MAX_TOKEN = 16_384;
    private static final int MAX_HEADER = 2_048;
    private static final int MAX_CLAIMS = 12_288;
    private static final int MAX_SIGNATURE = 2_048;
    private static final int MAX_DEPTH = 6;
    private static final int MAX_FIELDS = 64;

    private final ObjectMapper objectMapper;
    private final ObjectMapper tokenMapper;
    private final Clock clock;
    private final PublicKey publicKey;
    private final Policy policy;

    public ProductSurfaceStepUpChallengeVerifier(
            ObjectMapper objectMapper,
            Clock clock,
            PublicKey publicKey,
            Policy policy) {
        if (policy.maximumAuthenticationAgeSeconds() <= 0
                || policy.maximumChallengeTtlSeconds() <= 0) {
            throw new IllegalArgumentException("Step-up assurance lifetimes must be positive.");
        }
        this.objectMapper = objectMapper;
        this.tokenMapper = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.clock = clock;
        this.publicKey = publicKey;
        this.policy = policy.normalized();
    }

    public VerifiedChallenge verify(String compactToken, CommandBinding expected) {
        requireAvailable();
        if (compactToken == null || compactToken.isBlank()) {
            throw required("Step-up challenge is required.");
        }
        String token = compactToken.trim();
        if (token.length() > MAX_TOKEN) throw mismatch("Step-up challenge is too large.");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3
                || !segment(parts[0], MAX_HEADER)
                || !segment(parts[1], MAX_CLAIMS)
                || !segment(parts[2], MAX_SIGNATURE)) {
            throw mismatch("Step-up challenge format is invalid.");
        }

        JsonNode header = decode(parts[0]);
        exactFields(header, ProductSurfaceStepUpChallengeContract.HEADER_FIELDS, "header");
        if (!"RS256".equals(text(header, "alg"))
                || !"JWT".equals(text(header, "typ"))
                || !policy.keyId().equals(text(header, "kid"))) {
            throw mismatch("Step-up challenge signing metadata does not match policy.");
        }
        signature(parts);

        JsonNode claims = decode(parts[1]);
        if (!claims.has("auth_time") || !claims.has("acr") || !claims.has("amr")) {
            throw required("Step-up challenge assurance evidence is required.");
        }
        exactFields(claims, ProductSurfaceStepUpChallengeContract.CLAIM_FIELDS, "claims");
        Instant now = clock.instant();
        long issuedAt = epoch(claims, "iat", false);
        long expiresAt = epoch(claims, "exp", false);
        long notBefore = epoch(claims, "nbf", false);
        long authenticatedAt = epoch(claims, "auth_time", true);
        if (expiresAt <= issuedAt || notBefore < issuedAt - 30 || notBefore >= expiresAt
                || authenticatedAt > issuedAt + 30
                || notBefore > now.plusSeconds(30).getEpochSecond()
                || issuedAt > now.plusSeconds(30).getEpochSecond()
                || expiresAt - issuedAt > policy.maximumChallengeTtlSeconds()) {
            throw mismatch("Step-up challenge assurance window is invalid.");
        }
        if (expiresAt <= now.getEpochSecond()
                || authenticatedAt > now.plusSeconds(30).getEpochSecond()
                || now.getEpochSecond() - authenticatedAt
                > policy.maximumAuthenticationAgeSeconds()) {
            throw required("Fresh step-up authentication is required.");
        }

        claim(claims, "iss", policy.issuer());
        if (!audience(claims.get("aud"), policy.audience())) {
            throw mismatch("Step-up audience mismatch.");
        }
        if (!policy.requiredAcr().equals(text(claims, "acr"))) {
            throw required("Step-up acr assurance is required.");
        }
        if (!amr(claims.get("amr")).contains("mfa")) {
            throw required("Step-up MFA evidence is incomplete.");
        }
        claim(claims, "sub", Long.toString(expected.actorUserId()));
        longClaim(claims, "tenant_id", expected.tenantId());
        claim(claims, "owner_service_key", policy.ownerServiceKey());
        claim(claims, "command_contract_key", expected.commandContractKey());
        claim(claims, "activation_policy", expected.activationPolicy());
        claim(claims, "capability_contract_key", expected.capabilityContractKey());
        claim(claims, "context_key", expected.contextKey());
        claim(claims, "scope_ref", expected.scopeRef());
        claim(claims, "target_type", expected.targetType());
        claim(claims, "target_id", expected.targetId());
        longClaim(claims, "target_version", expected.targetVersion());
        claim(claims, "command_method", expected.commandMethod());
        claim(claims, "command_path", expected.commandPath());
        claim(claims, "idempotency_key", expected.idempotencyKey());
        claim(claims, "payload_sha256", expected.payloadSha256());
        claim(claims, "command_sha256", commandSha256(expected));
        claim(claims, "decision_revision", expected.decisionRevision());
        return new VerifiedChallenge(
                requiredText(claims, "jti", 160),
                requiredText(claims, "nonce", 160),
                policy.issuer(), expected, Instant.ofEpochSecond(expiresAt));
    }

    public String payloadSha256(Object payload) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(
                    canonical(objectMapper.valueToTree(payload)));
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "Step-up payload digest could not be computed.", exception);
        }
    }

    private void requireAvailable() {
        if (publicKey == null || policy.ownerServiceKey().isBlank()
                || policy.issuer().isBlank() || policy.audience().isBlank()
                || policy.keyId().isBlank() || policy.requiredAcr().isBlank()) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Step-up challenge verification is not configured.");
        }
    }

    private JsonNode decode(String value) {
        try {
            JsonNode decoded = tokenMapper.readTree(Base64.getUrlDecoder().decode(value));
            if (!decoded.isObject() || depth(decoded, 1) > MAX_DEPTH
                    || fields(decoded) > MAX_FIELDS) {
                throw mismatch("Step-up challenge JSON is invalid.");
            }
            return decoded;
        } catch (BaseException exception) {
            throw exception;
        } catch (IllegalArgumentException | java.io.IOException exception) {
            throw mismatch("Step-up challenge encoding is invalid.");
        }
    }

    private void signature(String[] parts) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1])
                    .getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw mismatch("Step-up challenge signature is invalid.");
            }
        } catch (BaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Step-up challenge signature verification is unavailable.", exception);
        }
    }

    private void exactFields(JsonNode node, Set<String> expected, String part) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw mismatch("Step-up challenge " + part + " schema is invalid.");
        }
    }

    private long epoch(JsonNode claims, String field, boolean assurance) {
        JsonNode value = claims.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw assurance ? required("Step-up challenge " + field + " is required.")
                    : mismatch("Step-up challenge " + field + " is required.");
        }
        return value.longValue();
    }

    private void claim(JsonNode claims, String field, String expected) {
        if (!expected.equals(text(claims, field))) {
            throw mismatch("Step-up challenge " + field + " mismatch.");
        }
    }

    private void longClaim(JsonNode claims, String field, long expected) {
        JsonNode value = claims.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() != expected) {
            throw mismatch("Step-up challenge " + field + " mismatch.");
        }
    }

    private String requiredText(JsonNode claims, String field, int maximum) {
        String raw = text(claims, field);
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank() || value.length() > maximum) {
            throw mismatch("Step-up challenge " + field + " is invalid.");
        }
        return value;
    }

    private String commandSha256(CommandBinding binding) {
        return ProductSurfaceStepUpChallengeContract.commandSha256(
                new ProductSurfaceStepUpChallengeContract.CommandMaterial(
                        binding.commandContractKey(), policy.ownerServiceKey(),
                        policy.audience(), binding.commandMethod(), binding.commandPath(),
                        binding.contextKey(), binding.scopeRef(), binding.targetType(),
                        binding.targetId(), binding.targetVersion(), binding.idempotencyKey(),
                        binding.payloadSha256(), binding.decisionRevision()));
    }

    private boolean segment(String value, int maximum) {
        if (value.isEmpty() || value.length() > maximum) return false;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z')
                    && !(c >= '0' && c <= '9') && c != '-' && c != '_') return false;
        }
        return true;
    }

    private boolean audience(JsonNode value, String expected) {
        if (value == null) return false;
        if (value.isTextual()) return expected.equals(value.textValue());
        return value.isArray() && value.size() == 1 && value.get(0).isTextual()
                && expected.equals(value.get(0).textValue());
    }

    private Set<String> amr(JsonNode value) {
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

    private int depth(JsonNode value, int current) {
        if (!value.isContainerNode()) return current;
        int maximum = current;
        for (JsonNode child : value) maximum = Math.max(maximum, depth(child, current + 1));
        return maximum;
    }

    private int fields(JsonNode value) {
        int count = value.isObject() ? value.size() : 0;
        for (JsonNode child : value) count += fields(child);
        return count;
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

    private String text(JsonNode value, String field) {
        JsonNode node = value.get(field);
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    public static PublicKey parsePublicKey(String pem) {
        if (pem == null || pem.isBlank()) return null;
        try {
            String encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (Exception exception) {
            throw new IllegalStateException("Configured step-up public key is invalid.", exception);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static BaseException mismatch(String message) {
        return new BaseException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH, message);
    }

    private static BaseException required(String message) {
        return new BaseException(ErrorCode.STEP_UP_REQUIRED, message);
    }

    public record Policy(
            String ownerServiceKey,
            String issuer,
            String audience,
            String keyId,
            String requiredAcr,
            long maximumAuthenticationAgeSeconds,
            long maximumChallengeTtlSeconds) {
        Policy normalized() {
            return new Policy(normalize(ownerServiceKey), normalize(issuer), normalize(audience),
                    normalize(keyId), normalize(requiredAcr), maximumAuthenticationAgeSeconds,
                    maximumChallengeTtlSeconds);
        }
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
