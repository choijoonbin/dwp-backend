package com.dwp.services.auth.workflowruntime;

import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeJson.*;
import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProtocol.*;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class WorkflowRuntimeProofVerifier {
    private final WorkflowRuntimeJson json;
    private final WorkflowRuntimeKeys keys;
    private final Clock clock;
    @Autowired public WorkflowRuntimeProofVerifier(WorkflowRuntimeJson json, WorkflowRuntimeKeys keys) { this(json, keys, Clock.systemUTC()); }
    public WorkflowRuntimeProofVerifier(WorkflowRuntimeJson json, WorkflowRuntimeKeys keys, Clock clock) { this.json = json; this.keys = keys; this.clock = clock; }
    public Verified verify(String transportToken, byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0 || rawBody.length > MAX_BODY) throw denied();
        JsonNode body = json.read(rawBody); exact(body, Set.of("operation", "sourceProof", "bindings"));
        Operation operation;
        try { operation = Operation.valueOf(text(body, "operation")); } catch (IllegalArgumentException exception) { throw denied(); }
        String proof = text(body, "sourceProof", MAX_OWNER_PROOF);
        JsonNode transport = jwt(transportToken, 2048, keys.transports(), TRANSPORT_CLAIMS);
        JsonNode owner = jwt(proof, MAX_OWNER_PROOF, keys.owners(), OWNER_CLAIMS);
        String bodySha = sha(json.canonical(body)), bindingsSha = sha(json.canonical(body.get("bindings")));
        if (!TRANSPORT_ISSUER.equals(text(transport, "iss")) || !TRANSPORT_AUDIENCE.equals(text(transport, "aud"))
                || !TRANSPORT_PURPOSE.equals(text(transport, "purpose")) || !operation.name().equals(text(transport, "operation"))
                || !"POST".equals(text(transport, "httpMethod")) || !PATH.equals(text(transport, "httpPath"))
                || !bodySha.equals(hash(transport, "bodySha256")) || !sha(proof).equals(hash(transport, "sourceProofSha256"))
                || !operation.ownerIssuer().equals(text(owner, "iss")) || !operation.ownerAudience().equals(text(owner, "aud"))
                || !operation.ownerPurpose().equals(text(owner, "purpose")) || !operation.name().equals(text(owner, "operation"))
                || !body.get("bindings").equals(owner.get("bindings")) || !text(owner, "sub").equals(text(transport, "sub"))) throw denied();
        var caller = WorkflowRuntimeBindings.parse(operation, body.get("bindings"));
        if (!text(owner, "sub").equals(Long.toString(caller.actorId()))) throw denied();
        Instant expiry = Instant.ofEpochSecond(Math.min(number(owner, "exp", 1, Long.MAX_VALUE), number(transport, "exp", 1, Long.MAX_VALUE)));
        if (operation == Operation.INFORMATION_ADMISSION) {
            expiry = minimum(expiry, Instant.ofEpochSecond(number(body.get("bindings").get("command"), "authorityValidUntil", 1, Long.MAX_VALUE)));
        } else WorkflowRuntimePublishedDefinition.role(json, body.get("bindings").get("owner"), body.get("bindings").get("stage"));
        if (!clock.instant().isBefore(expiry)) throw denied();
        return new Verified(operation, caller, uuid(owner, "jti"), uuid(transport, "jti"), bodySha, bindingsSha, expiry);
    }
    public JsonNode jwt(String token, int max, Map<String, RSAKey> trusted, Set<String> fields) {
        if (token == null || token.isEmpty() || token.length() > max || !token.equals(token.strip())) throw denied();
        if (trusted.isEmpty()) throw unavailable();
        try {
            String[] parts = token.split("\\.", -1); if (parts.length != 3) throw denied();
            var header = json.read(part(parts[0])); exact(header, Set.of("alg", "typ", "kid"));
            if (!"RS256".equals(text(header, "alg")) || !"JWT".equals(text(header, "typ"))) throw denied();
            RSAKey key = trusted.get(text(header, "kid")); part(parts[2]);
            if (key == null || !SignedJWT.parse(token).verify(new RSASSAVerifier(key))) throw denied();
            var claims = json.read(part(parts[1])); exact(claims, fields);
            long issued = number(claims, "iat", 1, Long.MAX_VALUE), starts = number(claims, "nbf", 1, Long.MAX_VALUE), expiry = number(claims, "exp", 1, Long.MAX_VALUE);
            long now = clock.instant().getEpochSecond();
            if (issued > now || starts != issued || expiry <= now || expiry <= issued || expiry - issued > 30) throw denied();
            uuid(claims, "jti"); return claims;
        } catch (BaseException exception) { throw exception; } catch (Exception exception) { throw denied(); }
    }
    public static Instant minimum(Instant left, Instant right) { return right != null && right.isBefore(left) ? right : left; }
    public record Verified(Operation operation, WorkflowRuntimeBindings.Caller caller, UUID sourceProofJti, UUID transportProofJti,
            String bodySha256, String bindingsSha256, Instant expiresAt) { }
}
