package com.dwp.services.auth.workflowplanning;

import static com.dwp.services.auth.workflowplanning.PlanningJson.*;
import static com.dwp.services.auth.workflowplanning.PlanningProtocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/** Crypto and the full HTTP body digest are verified before any Auth, replay or membership read. */
public final class PlanningProofVerifier {
    private final PlanningJson json; private final PlanningKeys keys; private final Clock clock;
    public PlanningProofVerifier(PlanningJson json, PlanningKeys keys, Clock clock) { this.json=json; this.keys=keys; this.clock=clock; }
    public Verified verify(byte[] body, String token) {
        var envelope = json.parse(body, BODY_LIMIT); exact(envelope, Set.of("operation", "sourceProof", "bindings"));
        if (!OPERATION.equals(text(envelope, "operation", 40))) throw denied();
        String sourceProof = text(envelope, "sourceProof", OWNER_LIMIT);
        var owner = jwt(sourceProof, OWNER_LIMIT, keys::owner, Set.of("version", "sealed"), OWNER_ISSUER, OWNER_AUDIENCE, OWNER_PURPOSE);
        var transport = jwt(token, TRANSPORT_LIMIT, keys::transport, Set.of("sourceProofJti", "sourceProofSha256", "bodySha256", "contextKey", "routeContractKey"),
                TRANSPORT_ISSUER, TRANSPORT_AUDIENCE, TRANSPORT_PURPOSE);
        var bindingJson = envelope.get("bindings");
        if (integer(owner.claims(), "version", 1) != 1 || !bindingJson.equals(owner.claims().get("sealed"))
                || !owner.jti().equals(uuid(transport.claims(), "sourceProofJti").toString()) || owner.jti().equals(transport.jti())
                || !sha(sourceProof).equals(hash(transport.claims(), "sourceProofSha256")) || !sha(body).equals(hash(transport.claims(), "bodySha256"))
                || !text(owner.claims(), "sub", 20).equals(text(transport.claims(), "sub", 20))) throw denied();
        var bindings = PlanningBindings.parse(json, bindingJson);
        if (!Long.toString(bindings.actorId()).equals(text(owner.claims(), "sub", 20))
                || !bindings.contextKey().equals(text(transport.claims(), "contextKey", 200)) || !ROUTE.equals(text(transport.claims(), "routeContractKey", 160))
                || !bindings.authorityValidUntil().isAfter(clock.instant()) || owner.expiresAt().isAfter(bindings.authorityValidUntil())
                || transport.expiresAt().isAfter(owner.expiresAt())) throw denied();
        return new Verified(bindings, owner.jti(), transport.jti(), sha(body), json.digest(bindingJson), transport.expiresAt(), owner.expiresAt());
    }
    private Token jwt(String raw, int limit, Function<String, RSAKey> key, Set<String> extras, String issuer, String audience, String purpose) {
        if (raw == null || raw.length() > limit || !raw.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw denied();
        try {
            var parts = raw.split("\\."); var header = json.parse(part(parts[0]), 2048); exact(header, Set.of("alg", "typ", "kid"));
            if (!"RS256".equals(text(header, "alg", 5)) || !"JWT".equals(text(header, "typ", 3))) throw denied(); part(parts[2]);
            if (!SignedJWT.parse(raw).verify(new RSASSAVerifier(key.apply(text(header, "kid", 80))))) throw denied();
            var claims = json.parse(part(parts[1]), BODY_LIMIT); var fields = new HashSet<>(STANDARD); fields.addAll(extras); exact(claims, fields);
            if (!issuer.equals(text(claims, "iss", 160)) || !audience.equals(text(claims, "aud", 160)) || !purpose.equals(text(claims, "purpose", 100))) throw denied();
            String id = uuid(claims, "jti").toString(); long issued = integer(claims, "iat", 1), start = integer(claims, "nbf", 1), expiry = integer(claims, "exp", 1), now=clock.instant().getEpochSecond();
            if (start != issued || issued > now || expiry <= now || expiry <= issued || expiry-issued > 30) throw denied();
            return new Token(claims, id, Instant.ofEpochSecond(expiry));
        } catch (Exception invalid) { throw denied(); }
    }
    private record Token(JsonNode claims, String jti, Instant expiresAt) { }
    public static final class Verified {
        private final PlanningBindings bindings; private final String sourceJti, transportJti, bodySha256, bindingsSha256; private final Instant expiresAt,replayUntil;
        private Verified(PlanningBindings bindings, String sourceJti, String transportJti, String bodySha256, String bindingsSha256, Instant expiresAt,Instant replayUntil) {
            this.bindings=bindings; this.sourceJti=sourceJti; this.transportJti=transportJti; this.bodySha256=bodySha256; this.bindingsSha256=bindingsSha256; this.expiresAt=expiresAt; this.replayUntil=replayUntil;
        }
        public PlanningBindings bindings() { return bindings; }
        public String sourceJti() { return sourceJti; }
        public String transportJti() { return transportJti; }
        public String bodySha256() { return bodySha256; }
        public String bindingsSha256() { return bindingsSha256; }
        public Instant expiresAt() { return expiresAt; }
        Instant replayUntil() { return replayUntil; }
    }
}
