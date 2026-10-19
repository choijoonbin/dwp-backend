package com.dwp.services.auth.hrisperformancefeature;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Exact reference-configuration proof. Approval bridge proof semantics are not reused. */
public final class PerformanceFeatureWorkloadVerifier {
    public static final String CALLER = "dwp-platform-server";
    public static final String AUDIENCE = "dwp-auth-server";
    private static final Set<String> CLAIMS = Set.of("iss", "aud", "sub", "iat", "nbf", "exp",
            "jti", "requestNonce", "bindingId", "purpose", "method", "path", "tenantId",
            "requestBodySha256", "authn");
    private static final Set<String> SECURITY_HEADERS = Set.of("x-dwp-workload-proof", "x-dwp-service-identity");
    private static final Set<String> BROWSER_HEADERS = Set.of("authorization", "cookie", "origin", "referer",
            "x-user-id", "x-tenant-id", "x-roles", "x-permissions", "x-forwarded-user", "x-forwarded-email");
    private static final Set<String> REQUEST_FIELDS = Set.of("tenantId", "subjectPrincipalPublicId",
            "exactSharedActionRouteContractKey", "configurationId", "resolvedDomain", "scopeDigest",
            "commandAuthorizationRevision", "correlationId");
    private final ObjectMapper json;
    private final Clock clock;
    private final SigningKeys keys;
    private final ReplayStore replay;

    public record Header(String name, String value) { }
    public record Key(PublicKey publicKey, String authenticatedWorkload, String authentication) { }
    @FunctionalInterface public interface SigningKeys { Key resolve(String keyId); }
    /** Implementations must durably and atomically reserve both identities across replicas. */
    @FunctionalInterface public interface ReplayStore {
        boolean reserve(UUID jti, UUID nonce, long tenantId, Instant expiresAt);
    }

    public PerformanceFeatureWorkloadVerifier(ObjectMapper mapper, Clock clock,
            SigningKeys keys, ReplayStore replay) {
        this.json = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = clock; this.keys = keys; this.replay = replay;
    }

    public PerformanceFeatureContract.Request verify(String method, String path, String query,
            List<Header> rawHeaders, byte[] body) {
        try {
            if (!"POST".equals(method) || !PerformanceFeatureContract.PATH.equals(path)
                    || query != null || body == null || body.length == 0 || body.length > 16384) throw denied();
            var headers = new HashMap<String, String>();
            for (Header header : rawHeaders) {
                if (header == null || header.name() == null || header.value() == null) throw denied();
                String name = header.name().toLowerCase(Locale.ROOT), value = header.value();
                if (value.contains("\r") || value.contains("\n") || !value.equals(value.strip())
                        || headers.putIfAbsent(name, value) != null || BROWSER_HEADERS.contains(name)
                        || name.startsWith("sec-fetch-") || name.startsWith("sec-ch-ua")
                        || (name.startsWith("x-dwp-") && !SECURITY_HEADERS.contains(name))) throw denied();
            }
            String contentType = headers.get("content-type");
            if (contentType == null || !Set.of("application/json", "application/json;charset=utf-8")
                    .contains(contentType.toLowerCase(Locale.ROOT).replace(" ", ""))
                    || !CALLER.equals(headers.get("x-dwp-service-identity"))) throw denied();
            String token = headers.get("x-dwp-workload-proof");
            if (token == null || token.length() > 16384) throw denied();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw denied();
            JsonNode jose = json.readTree(decode(parts[0]));
            exact(jose, Set.of("alg", "typ", "kid"));
            String keyId = text(jose, "kid");
            if (!"RS256".equals(text(jose, "alg")) || !"JWT".equals(text(jose, "typ"))
                    || !keyId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{2,127}")) throw denied();
            Key key = keys.resolve(keyId);
            if (key == null || !(key.publicKey() instanceof RSAPublicKey rsa)
                    || rsa.getModulus().bitLength() < 2048 || !CALLER.equals(key.authenticatedWorkload())
                    || !Set.of("MTLS_SPIFFE", "CLIENT_CREDENTIAL_JWT").contains(key.authentication())) throw denied();
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key.publicKey());
            signature.update((parts[0] + '.' + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(decode(parts[2]))) throw denied();
            JsonNode claims = json.readTree(decode(parts[1])); exact(claims, CLAIMS);
            long issued = integer(claims, "iat"), starts = integer(claims, "nbf"), expiry = integer(claims, "exp");
            long now = clock.instant().getEpochSecond();
            if (!CALLER.equals(text(claims, "iss")) || !CALLER.equals(text(claims, "sub"))
                    || !AUDIENCE.equals(text(claims, "aud"))
                    || !PerformanceFeatureContract.BINDING.equals(text(claims, "bindingId"))
                    || !PerformanceFeatureContract.PURPOSE.equals(text(claims, "purpose"))
                    || !method.equals(text(claims, "method")) || !path.equals(text(claims, "path"))
                    || !key.authentication().equals(text(claims, "authn"))
                    || !PerformanceFeatureContract.sha256(body).equals(text(claims, "requestBodySha256"))
                    || issued > now || starts != issued || expiry <= now || expiry <= issued || expiry - issued > 30) throw denied();
            UUID jti = uuid(claims, "jti"), nonce = uuid(claims, "requestNonce");
            if (jti.equals(nonce)) throw denied();
            JsonNode requestJson = json.readTree(body); exact(requestJson, REQUEST_FIELDS);
            // Reject numeric coercion as well as duplicate/unknown fields before any provider call.
            integer(requestJson, "tenantId"); integer(requestJson, "commandAuthorizationRevision");
            for (String field : REQUEST_FIELDS) {
                if (!Set.of("tenantId", "commandAuthorizationRevision").contains(field)) text(requestJson, field);
            }
            var request = json.treeToValue(requestJson, PerformanceFeatureContract.Request.class);
            if (integer(claims, "tenantId") != request.tenantId()
                    || !replay.reserve(jti, nonce, request.tenantId(), Instant.ofEpochSecond(expiry))) throw denied();
            return request;
        } catch (Exception failure) {
            throw denied();
        }
    }

    private static byte[] decode(String value) {
        if (value.isEmpty() || !value.matches("[A-Za-z0-9_-]+")) throw denied();
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) throw denied();
        return decoded;
    }
    private static void exact(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw denied();
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw denied();
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || !value.textValue().equals(value.textValue().strip())) throw denied();
        return value.textValue();
    }
    private static long integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) throw denied();
        return value.longValue();
    }
    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        if (!PerformanceFeatureContract.uuid(value)) throw denied();
        return UUID.fromString(value);
    }
    private static IllegalArgumentException denied() {
        return new IllegalArgumentException("Dedicated reference-configuration workload admission required.");
    }
}
