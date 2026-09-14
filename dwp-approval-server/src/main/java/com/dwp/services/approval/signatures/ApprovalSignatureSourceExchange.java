package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/** Native owner and transport proof; neither proof is a user capability or artifact signing authority. */
final class ApprovalSignatureSourceExchange {
    static final String PATH = "/internal/auth/v1/approval-signature-authority/evaluate";
    static final String HEADER = "X-DWP-Approval-Signature-Source-Token";
    private final ApprovalSignatureSourceKeys keys;
    private final ApprovalSignatureCanonical canonical;
    private final Clock clock;
    ApprovalSignatureSourceExchange(ApprovalSignatureSourceKeys keys, ApprovalSignatureCanonical canonical, Clock clock) {
        this.keys = keys; this.canonical = canonical; this.clock = clock;
    }
    record Exchange(byte[] body, String token, Map<String, Object> bindings) {
        Exchange { body = body.clone(); bindings = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(bindings)); }
        @Override public byte[] body() { return body.clone(); }
    }
    Exchange issue(Map<String, Object> bindings, Instant expiry) {
        Instant now = clock.instant(); expiry = Instant.ofEpochSecond(expiry.getEpochSecond());
        if (!expiry.isAfter(now) || expiry.isAfter(now.plusSeconds(30))) throw denied();
        String digest = canonical.digest(bindings), ownerId = UUID.randomUUID().toString();
        String owner = sign(keys.owner, base("dwp-approval-signature-owner", "dwp-auth-signature-source", now, expiry, ownerId)
                .claim("purpose", "DWP_APPROVAL_SIGNATURE_OWNER_SOURCE_V1").claim("bindings", bindings).claim("bindingsSha256", digest).build());
        byte[] body = canonical.json(Map.of("sourceProof", owner, "bindings", bindings)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (body.length > 65536) throw denied();
        String transport = sign(keys.transport, base("dwp-approval-signature-transport", "dwp-auth-signature-authority", now, expiry, UUID.randomUUID().toString())
                .claim("purpose", "DWP_APPROVAL_SIGNATURE_TRANSPORT_V1").claim("method", "POST").claim("path", PATH)
                .claim("sourceProofJti", ownerId).claim("bodySha256", sha(body)).claim("bindingsSha256", digest).build());
        return new Exchange(body, transport, bindings);
    }
    private static JWTClaimsSet.Builder base(String issuer, String audience, Instant now, Instant expiry, String id) {
        return new JWTClaimsSet.Builder().issuer(issuer).claim("aud", audience).subject("dwp-approval-server").jwtID(id)
                .issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(expiry));
    }
    private static String sign(RSAKey key, JWTClaimsSet claims) {
        try { var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key)); return jwt.serialize(); }
        catch (Exception unavailable) { throw unavailable(); }
    }
}
