package com.dwp.gateway.filter;

import com.dwp.gateway.security.ResourceRoleEvidence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.server.reactive.ServerHttpRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

final class DelegatedIdentityAssertionIssuer {

    static final String HEADER = "X-DWP-Delegated-Identity";
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final byte[] signingSecret;
    private final String keyId;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Supplier<UUID> nonceSupplier;

    DelegatedIdentityAssertionIssuer(
            String signingSecret,
            String keyId,
            ObjectMapper objectMapper) {
        this(signingSecret, keyId, objectMapper, Clock.systemUTC(), UUID::randomUUID);
    }

    DelegatedIdentityAssertionIssuer(
            String signingSecret,
            String keyId,
            ObjectMapper objectMapper,
            Clock clock,
            Supplier<UUID> nonceSupplier) {
        this.signingSecret = signingSecret.trim().getBytes(StandardCharsets.UTF_8);
        this.keyId = keyId.isBlank() ? "gateway-agent-v1" : keyId.trim();
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.nonceSupplier = nonceSupplier;
    }

    boolean enabled() {
        return signingSecret.length > 0;
    }

    String issue(ServerHttpRequest request) {
        if (!enabled()) return null;
        long issuedAt = Instant.now(clock).getEpochSecond();
        Map<String, Object> protectedHeader = new TreeMap<>();
        protectedHeader.put("alg", "HS256");
        protectedHeader.put("kid", keyId);
        protectedHeader.put("typ", "dwp-identity+jwt");

        Map<String, Object> claims = new TreeMap<>();
        claims.put("aud", "dwp-agent");
        claims.put("cid", header(request, "X-Correlation-ID"));
        claims.put("dn", header(request, VerifiedIdentityFilter.DISPLAY_NAME_HEADER));
        claims.put("exp", issuedAt + 15);
        claims.put("htm", request.getMethod().name());
        claims.put("htu", downstreamPath(request));
        claims.put("iat", issuedAt);
        claims.put("ip", header(request, VerifiedIdentityFilter.IDENTITY_PLANE_HEADER));
        claims.put("iss", "dwp-gateway");
        claims.put("jti", nonceSupplier.get().toString());
        claims.put("nbf", issuedAt - 1);
        claims.put("permissions", values(header(request, VerifiedIdentityFilter.PERMISSIONS_HEADER)));
        claims.put("pid", header(request, VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER));
        claims.put("resourceRoles", ResourceRoleEvidence.parseHeaderStrict(
                header(request, VerifiedIdentityFilter.RESOURCE_ROLES_HEADER)));
        claims.put("roles", values(header(request, VerifiedIdentityFilter.ROLES_HEADER)));
        claims.put("sid", header(request, VerifiedIdentityFilter.AUTH_SESSION_ID_HEADER));
        claims.put("sub", header(request, VerifiedIdentityFilter.USER_HEADER));
        claims.put("tid", header(request, VerifiedIdentityFilter.TENANT_HEADER));

        try {
            String encodedHeader = encode(objectMapper.writeValueAsBytes(protectedHeader));
            String encodedClaims = encode(objectMapper.writeValueAsBytes(claims));
            String signingInput = encodedHeader + "." + encodedClaims;
            return signingInput + "." + encode(sign(signingInput));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Delegated identity assertion could not be encoded.", exception);
        }
    }

    private byte[] sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Delegated identity assertion could not be signed.", exception);
        }
    }

    private String downstreamPath(ServerHttpRequest request) {
        String path = request.getURI().getRawPath();
        String prefix = "/api/agent";
        if (!path.startsWith(prefix)) return path;
        String downstream = path.substring(prefix.length());
        return downstream.isBlank() ? "/" : downstream;
    }

    private String header(ServerHttpRequest request, String name) {
        return request.getHeaders().getFirst(name);
    }

    private List<String> values(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> values = new ArrayList<>();
        for (String item : value.split(",")) {
            String candidate = item.trim().toUpperCase(Locale.ROOT);
            if (!candidate.isBlank() && !values.contains(candidate)) values.add(candidate);
        }
        values.sort(String::compareTo);
        return List.copyOf(values);
    }

    private String encode(byte[] value) {
        return BASE64_URL.encodeToString(value);
    }
}
