package com.dwp.services.auth.informationreplay;

import static com.dwp.services.auth.informationreplay.InformationReplayJson.*;
import static com.dwp.services.auth.informationreplay.InformationReplayProtocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class InformationReplayProofVerifier {
    private final InformationReplayJson json;
    private final InformationReplayKeys keys;
    private final Clock clock;
    public InformationReplayProofVerifier(InformationReplayJson json, InformationReplayKeys keys, Clock clock) {
        this.json = json; this.keys = keys; this.clock = clock;
    }
    public Verified verify(byte[] bytes, String transportToken) {
        var body = json.parse(bytes, BODY_LIMIT); exact(body, Set.of("operation", "sourceProof", "bindings"));
        if (!OPERATION.equals(text(body, "operation", 20))) throw denied();
        String sourceToken = text(body, "sourceProof", OWNER_LIMIT);
        var owner = jwt(sourceToken, true); exact(owner, OWNER_CLAIMS);
        var transport = jwt(transportToken, false); exact(transport, TRANSPORT_CLAIMS);
        if (!OWNER_ISSUER.equals(text(owner, "iss", 160)) || !OWNER_AUDIENCE.equals(text(owner, "aud", 160))
                || !OWNER_PURPOSE.equals(text(owner, "purpose", 100)) || number(owner, "version", 1, 1) != 1
                || !TRANSPORT_ISSUER.equals(text(transport, "iss", 160)) || !TRANSPORT_AUDIENCE.equals(text(transport, "aud", 160))
                || !TRANSPORT_PURPOSE.equals(text(transport, "purpose", 100))) throw denied();
        var bindings = body.get("bindings"); var caller = InformationReplayBindings.validate(json, bindings);
        com.dwp.services.auth.workflowruntime.WorkflowRuntimePublishedDefinition.role(
                new com.dwp.services.auth.workflowruntime.WorkflowRuntimeJson(new com.fasterxml.jackson.databind.ObjectMapper()), bindings.get("owner"), bindings.get("source"));
        String subject = Long.toString(caller.actorId());
        if (!subject.equals(text(owner, "sub", 20)) || !subject.equals(text(transport, "sub", 20))
                || !json.canonical(owner.get("sealed")).equals(json.canonical(bindings))) throw denied();
        UUID sourceJti = uuid(owner, "jti"), transportJti = uuid(transport, "jti");
        String bodySha = json.digest(body), bindingsSha = json.digest(bindings);
        if (sourceJti.equals(transportJti) || !sourceJti.equals(uuid(transport, "sourceProofJti"))
                || !sha(sourceToken).equals(hash(transport, "sourceProofSha256")) || !bodySha.equals(hash(transport, "bodySha256"))
                || !caller.contextKey().equals(text(transport, "contextKey", 500)) || !ROUTE.equals(text(transport, "routeContractKey", 100))) throw denied();
        Instant deadline = earlier(time(owner), time(transport));
        if (number(bindings.get("admission"), "acceptedAt", 1, SAFE_INTEGER) > clock.instant().getEpochSecond()) throw denied();
        return new Verified(caller, sourceJti, transportJti, bodySha, bindingsSha, deadline);
    }
    private JsonNode jwt(String token, boolean owner) {
        if (token == null || token.length() > (owner ? OWNER_LIMIT : TRANSPORT_LIMIT)) throw denied();
        String[] parts = token.split("\\.", -1); if (parts.length != 3) throw denied();
        var header = json.parse(part(parts[0]), 2048); exact(header, Set.of("alg", "typ", "kid"));
        if (!"RS256".equals(text(header, "alg", 5)) || !"JWT".equals(text(header, "typ", 3))) throw denied();
        String kid = text(header, "kid", 80); part(parts[2]);
        RSAKey key = owner ? keys.owner(kid) : keys.transport(kid);
        try { if (!SignedJWT.parse(token).verify(new RSASSAVerifier(key))) throw denied(); }
        catch (com.dwp.core.exception.BaseException error) { throw error; }
        catch (Exception invalid) { throw denied(); }
        return json.parse(part(parts[1]), owner ? OWNER_LIMIT : 4096);
    }
    private Instant time(JsonNode claims) {
        long issued = number(claims, "iat", 1, SAFE_INTEGER), starts = number(claims, "nbf", 1, SAFE_INTEGER);
        long expires = number(claims, "exp", 1, SAFE_INTEGER), now = clock.instant().getEpochSecond();
        if (issued > now || starts != issued || expires <= now || expires <= issued || expires - issued > 30) throw denied();
        return Instant.ofEpochSecond(expires);
    }
    public static Instant earlier(Instant left, Instant right) { return right != null && right.isBefore(left) ? right : left; }
    public static final class Verified {
        private final InformationReplayBindings.Caller caller;
        private final UUID sourceJti, transportJti;
        private final String bodySha256, bindingsSha256;
        private final Instant expiresAt;
        private Verified(InformationReplayBindings.Caller caller, UUID sourceJti, UUID transportJti, String bodySha256, String bindingsSha256, Instant expiresAt) {
            this.caller = caller; this.sourceJti = sourceJti; this.transportJti = transportJti;
            this.bodySha256 = bodySha256; this.bindingsSha256 = bindingsSha256; this.expiresAt = expiresAt;
        }
        public InformationReplayBindings.Caller caller() { return caller; }
        public UUID sourceJti() { return sourceJti; }
        public UUID transportJti() { return transportJti; }
        public String bodySha256() { return bodySha256; }
        public String bindingsSha256() { return bindingsSha256; }
        public Instant expiresAt() { return expiresAt; }
    }
}
