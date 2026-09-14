package com.dwp.services.auth.approvalpolicyimpact;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

public final class PolicyImpactProofVerifier {
    private final PolicyImpactJson json;
    private final PolicyImpactKeys keys;
    private final Clock clock;
    public PolicyImpactProofVerifier(PolicyImpactJson json, PolicyImpactKeys keys, Clock clock) {
        this.json = json; this.keys = keys; this.clock = clock;
    }
    public Verified verify(byte[] rawBody, String transportToken) {
        var body = json.parse(rawBody); PolicyImpactJson.keys(body, Set.of("sourceProof", "bindings"));
        var bindings = PolicyImpactBindings.parse(body.get("bindings"));
        var bindingJson = body.get("bindings"); var bindingHash = json.digest(bindingJson);
        var owner = token(PolicyImpactJson.text(body, "sourceProof", PolicyImpactProtocol.OWNER_LIMIT),
                PolicyImpactProtocol.OWNER_LIMIT, keys::owner,
                Set.of("purpose", "bindings", "bindingsSha256"), PolicyImpactProtocol.OWNER_ISSUER,
                PolicyImpactProtocol.OWNER_AUDIENCE, "dwp-approval-server", PolicyImpactProtocol.OWNER_PURPOSE);
        if (!owner.claims().get("bindings").equals(bindingJson)
                || !bindingHash.equals(PolicyImpactJson.hash(owner.claims(), "bindingsSha256"))) throw PolicyImpactJson.denied();
        var transport = token(transportToken, PolicyImpactProtocol.TRANSPORT_LIMIT, keys::transport,
                Set.of("purpose", "method", "path", "sourceProofJti", "bodySha256", "bindingsSha256"),
                PolicyImpactProtocol.TRANSPORT_ISSUER, PolicyImpactProtocol.TRANSPORT_AUDIENCE,
                "dwp-approval-server", PolicyImpactProtocol.TRANSPORT_PURPOSE);
        if (!"POST".equals(PolicyImpactJson.text(transport.claims(), "method", 4))
                || !PolicyImpactProtocol.PATH.equals(PolicyImpactJson.text(transport.claims(), "path", 100))
                || !owner.jti().equals(PolicyImpactJson.text(transport.claims(), "sourceProofJti", 80))
                || !PolicyImpactJson.sha(rawBody).equals(PolicyImpactJson.hash(transport.claims(), "bodySha256"))
                || !bindingHash.equals(PolicyImpactJson.hash(transport.claims(), "bindingsSha256"))
                || owner.jti().equals(transport.jti()) || !bindings.authorityValidUntil().isAfter(clock.instant())
                || owner.expiresAt().isAfter(bindings.authorityValidUntil())
                || transport.expiresAt().isAfter(owner.expiresAt())) throw PolicyImpactJson.denied();
        return new Verified(bindings, bindingJson.deepCopy(), owner.jti(), transport.jti(),
                PolicyImpactJson.sha(rawBody), bindingHash, transport.expiresAt());
    }
    private Token token(String raw, int limit, Function<String, RSAKey> key, Set<String> extras,
            String issuer, String audience, String subject, String purpose) {
        if (raw == null || raw.length() > limit || !raw.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw PolicyImpactJson.denied();
        try {
            String[] parts = raw.split("\\.");
            var header = json.parse(Base64.getUrlDecoder().decode(parts[0]));
            PolicyImpactJson.keys(header, Set.of("alg", "typ", "kid"));
            if (!"RS256".equals(PolicyImpactJson.text(header, "alg", 10)) || !"JWT".equals(PolicyImpactJson.text(header, "typ", 10))) throw PolicyImpactJson.denied();
            var claims = json.parse(Base64.getUrlDecoder().decode(parts[1]));
            var fields = new HashSet<>(PolicyImpactProtocol.STANDARD); fields.addAll(extras); PolicyImpactJson.keys(claims, fields);
            if (!issuer.equals(PolicyImpactJson.text(claims, "iss", 100))
                    || !audience.equals(PolicyImpactJson.text(claims, "aud", 100))
                    || !subject.equals(PolicyImpactJson.text(claims, "sub", 100))
                    || !purpose.equals(PolicyImpactJson.text(claims, "purpose", 100))) throw PolicyImpactJson.denied();
            String jti = PolicyImpactJson.text(claims, "jti", 80);
            if (!jti.matches("[a-zA-Z0-9_-]{20,80}")) throw PolicyImpactJson.denied();
            Instant issued = Instant.ofEpochSecond(PolicyImpactJson.integer(claims, "iat", false));
            Instant start = Instant.ofEpochSecond(PolicyImpactJson.integer(claims, "nbf", false));
            Instant expires = Instant.ofEpochSecond(PolicyImpactJson.integer(claims, "exp", false));
            if (!start.equals(issued) || issued.isAfter(clock.instant()) || !expires.isAfter(clock.instant())
                    || !expires.isAfter(issued) || expires.isAfter(issued.plusSeconds(30))) throw PolicyImpactJson.denied();
            SignedJWT jwt = SignedJWT.parse(raw);
            if (!jwt.verify(new RSASSAVerifier(key.apply(PolicyImpactJson.text(header, "kid", 80)).toRSAPublicKey()))) throw PolicyImpactJson.denied();
            return new Token(claims, jti, expires);
        } catch (Exception error) { throw PolicyImpactJson.denied(); }
    }
    private record Token(JsonNode claims, String jti, Instant expiresAt) { }
    public static final class Verified {
        private final PolicyImpactBindings bindings;
        private final JsonNode bindingJson;
        private final String sourceJti, transportJti, bodySha256, bindingsSha256;
        private final Instant expiresAt;
        private Verified(PolicyImpactBindings bindings, JsonNode bindingJson, String sourceJti, String transportJti,
                String bodySha256, String bindingsSha256, Instant expiresAt) {
            this.bindings = bindings; this.bindingJson = bindingJson; this.sourceJti = sourceJti; this.transportJti = transportJti;
            this.bodySha256 = bodySha256; this.bindingsSha256 = bindingsSha256; this.expiresAt = expiresAt;
        }
        public PolicyImpactBindings bindings() { return bindings; }
        public JsonNode bindingJson() { return bindingJson.deepCopy(); }
        public String sourceJti() { return sourceJti; }
        public String transportJti() { return transportJti; }
        public String bodySha256() { return bodySha256; }
        public String bindingsSha256() { return bindingsSha256; }
        public Instant expiresAt() { return expiresAt; }
    }
}
