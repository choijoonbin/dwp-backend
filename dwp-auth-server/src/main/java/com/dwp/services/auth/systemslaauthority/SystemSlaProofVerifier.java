package com.dwp.services.auth.systemslaauthority;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.function.Function;

public final class SystemSlaProofVerifier {
    private final SystemSlaJson json;
    private final SystemSlaKeys keys;
    private final Clock clock;
    public SystemSlaProofVerifier(SystemSlaJson json, SystemSlaKeys keys, Clock clock) { this.json = json; this.keys = keys; this.clock = clock; }
    public Verified verify(byte[] bytes, String token) {
        var body = json.parse(bytes); SystemSlaJson.keys(body, Set.of("sourceProof", "bindings"));
        var bindings = SystemSlaBindings.parse(body.get("bindings"), json); String bindingsHash = json.digest(body.get("bindings"));
        var owner = token(SystemSlaJson.text(body, "sourceProof", SystemSlaProtocol.OWNER_LIMIT), SystemSlaProtocol.OWNER_LIMIT, keys::owner,
                Set.of("purpose", "bindingsSha256", "sourceDigest"), SystemSlaProtocol.OWNER_ISSUER, SystemSlaProtocol.OWNER_AUDIENCE, SystemSlaProtocol.OWNER_PURPOSE);
        if (!bindingsHash.equals(SystemSlaJson.hash(owner.claims(), "bindingsSha256"))
                || !bindings.sourceDigest().equals(SystemSlaJson.hash(owner.claims(), "sourceDigest"))) throw SystemSlaJson.denied();
        var transport = token(token, SystemSlaProtocol.TRANSPORT_LIMIT, keys::transport,
                Set.of("purpose", "method", "path", "sourceProofJti", "bodySha256", "bindingsSha256"),
                SystemSlaProtocol.TRANSPORT_ISSUER, SystemSlaProtocol.TRANSPORT_AUDIENCE, SystemSlaProtocol.TRANSPORT_PURPOSE);
        if (!"POST".equals(SystemSlaJson.text(transport.claims(), "method", 4))
                || !SystemSlaProtocol.PATH.equals(SystemSlaJson.text(transport.claims(), "path", 100))
                || !owner.jti().equals(SystemSlaJson.text(transport.claims(), "sourceProofJti", 80)) || owner.jti().equals(transport.jti())
                || !bindingsHash.equals(SystemSlaJson.hash(transport.claims(), "bindingsSha256"))
                || !SystemSlaJson.sha(bytes).equals(SystemSlaJson.hash(transport.claims(), "bodySha256"))
                || !bindings.validUntil().isAfter(clock.instant()) || owner.expiresAt().isAfter(bindings.validUntil())
                || transport.expiresAt().isAfter(owner.expiresAt())
                || SystemSlaJson.instant(bindings.source().get("timer"), "dueAt").isAfter(clock.instant())) throw SystemSlaJson.denied();
        for (var review : bindings.source().get("policy").get("reviewedHistory"))
            if (SystemSlaJson.instant(review, "publishedAt").isAfter(clock.instant())) throw SystemSlaJson.notReviewed();
        return new Verified(bindings, bindingsHash, owner.jti(), transport.jti(), SystemSlaJson.sha(bytes), transport.expiresAt(), owner.expiresAt());
    }
    private Token token(String raw, int limit, Function<String, RSAKey> key, Set<String> extras, String issuer, String audience, String purpose) {
        if (raw == null || raw.length() > limit || !raw.matches("[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+")) throw SystemSlaJson.denied();
        try {
            var parts = raw.split("\\."); var decoder = java.util.Base64.getUrlDecoder();
            var header = json.parse(decoder.decode(parts[0])); SystemSlaJson.keys(header, Set.of("alg", "typ", "kid"));
            if (!"RS256".equals(SystemSlaJson.text(header, "alg", 10)) || !"JWT".equals(SystemSlaJson.text(header, "typ", 10))) throw SystemSlaJson.denied();
            var claims = json.parse(decoder.decode(parts[1])); var fields = new java.util.HashSet<>(SystemSlaProtocol.STANDARD); fields.addAll(extras); SystemSlaJson.keys(claims, fields);
            if (!issuer.equals(SystemSlaJson.text(claims, "iss", 100)) || !audience.equals(SystemSlaJson.text(claims, "aud", 100))
                    || !"dwp-approval-server".equals(SystemSlaJson.text(claims, "sub", 100)) || !purpose.equals(SystemSlaJson.text(claims, "purpose", 100))) throw SystemSlaJson.denied();
            String jti = SystemSlaJson.text(claims, "jti", 80); if (!jti.matches("[a-zA-Z0-9_-]{20,80}")) throw SystemSlaJson.denied();
            Instant issued = Instant.ofEpochSecond(SystemSlaJson.integer(claims, "iat", false)), start = Instant.ofEpochSecond(SystemSlaJson.integer(claims, "nbf", false));
            Instant expires = Instant.ofEpochSecond(SystemSlaJson.integer(claims, "exp", false));
            if (!issued.equals(start) || issued.isAfter(clock.instant()) || !expires.isAfter(clock.instant())
                    || !expires.isAfter(issued) || expires.isAfter(issued.plusSeconds(30))) throw SystemSlaJson.denied();
            if (!SignedJWT.parse(raw).verify(new RSASSAVerifier(key.apply(SystemSlaJson.text(header, "kid", 80)).toRSAPublicKey()))) throw SystemSlaJson.denied();
            return new Token(claims, jti, expires);
        } catch (Exception error) { throw SystemSlaJson.denied(); }
    }
    private record Token(JsonNode claims, String jti, Instant expiresAt) { }
    public static final class Verified {
        private final SystemSlaBindings bindings;
        private final String bindingsHash, ownerJti, transportJti, bodyHash;
        private final Instant expiresAt, ownerExpiresAt;
        private Verified(SystemSlaBindings bindings, String bindingsHash, String ownerJti, String transportJti, String bodyHash, Instant expiresAt, Instant ownerExpiresAt) {
            this.bindings = bindings; this.bindingsHash = bindingsHash; this.ownerJti = ownerJti; this.transportJti = transportJti; this.bodyHash = bodyHash; this.expiresAt = expiresAt; this.ownerExpiresAt = ownerExpiresAt;
        }
        public SystemSlaBindings bindings() { return bindings; }
        public String bindingsHash() { return bindingsHash; }
        public String ownerJti() { return ownerJti; }
        public String transportJti() { return transportJti; }
        public String bodyHash() { return bodyHash; }
        public Instant expiresAt() { return expiresAt; }
        public Instant ownerExpiresAt() { return ownerExpiresAt; }
    }
}
