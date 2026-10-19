package com.dwp.services.auth.hrisperformancefeature;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class PerformanceWorkloadFixture {
    static final Instant NOW = Instant.parse("2026-10-19T05:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final KeyPair PAIR = pair();
    static final String KID = "per004-test-key";
    static final PerformanceFeatureContract.Request REQUEST = new PerformanceFeatureContract.Request(
            7, "10000000-0000-0000-0000-000000000001",
            "route.hcm.configuration.configuration-version-create.action",
            "20000000-0000-0000-0000-000000000002", "PER", "a".repeat(64), 42,
            "30000000-0000-0000-0000-000000000003");

    static byte[] body() throws Exception { return JSON.writeValueAsBytes(REQUEST); }
    static Map<String, Object> claims(byte[] body) {
        var claims = new HashMap<String, Object>();
        claims.put("iss", "dwp-platform-server"); claims.put("sub", "dwp-platform-server");
        claims.put("aud", "dwp-auth-server"); claims.put("iat", NOW.getEpochSecond());
        claims.put("nbf", NOW.getEpochSecond()); claims.put("exp", NOW.plusSeconds(30).getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString()); claims.put("requestNonce", UUID.randomUUID().toString());
        claims.put("bindingId", PerformanceFeatureContract.BINDING); claims.put("purpose", PerformanceFeatureContract.PURPOSE);
        claims.put("method", "POST"); claims.put("path", PerformanceFeatureContract.PATH);
        claims.put("tenantId", REQUEST.tenantId()); claims.put("requestBodySha256", PerformanceFeatureContract.sha256(body));
        claims.put("authn", "CLIENT_CREDENTIAL_JWT"); return claims;
    }
    static String token(Map<String, Object> claims) throws Exception {
        return sign(JSON.writeValueAsString(claims));
    }
    static String sign(String claimsJson) throws Exception {
        String header = encode(JSON.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT", "kid", KID)));
        String unsigned = header + '.' + encode(claimsJson.getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA"); signature.initSign(PAIR.getPrivate());
        signature.update(unsigned.getBytes(StandardCharsets.US_ASCII)); return unsigned + '.' + encode(signature.sign());
    }
    static List<PerformanceFeatureWorkloadVerifier.Header> headers(String token) {
        return new ArrayList<>(List.of(new PerformanceFeatureWorkloadVerifier.Header("Content-Type", "application/json"),
                new PerformanceFeatureWorkloadVerifier.Header("X-DWP-Service-Identity", "dwp-platform-server"),
                new PerformanceFeatureWorkloadVerifier.Header("X-DWP-Workload-Proof", token)));
    }
    static PerformanceFeatureWorkloadVerifier.SigningKeys keys() {
        return kid -> KID.equals(kid) ? new PerformanceFeatureWorkloadVerifier.Key(PAIR.getPublic(),
                "dwp-platform-server", "CLIENT_CREDENTIAL_JWT") : null;
    }
    static PerformanceFeatureWorkloadVerifier verifier(PerformanceFeatureWorkloadVerifier.ReplayStore replay) {
        return new PerformanceFeatureWorkloadVerifier(JSON, CLOCK, keys(), replay);
    }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static KeyPair pair() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
