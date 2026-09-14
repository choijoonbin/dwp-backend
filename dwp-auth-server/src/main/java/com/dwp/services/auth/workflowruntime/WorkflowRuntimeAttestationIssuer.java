package com.dwp.services.auth.workflowruntime;

import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeJson.*;
import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProtocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class WorkflowRuntimeAttestationIssuer {
    private final WorkflowRuntimeJson json;
    private final WorkflowRuntimeKeys keys;
    private final Clock clock;
    @Autowired public WorkflowRuntimeAttestationIssuer(WorkflowRuntimeJson json, WorkflowRuntimeKeys keys) { this(json, keys, Clock.systemUTC()); }
    public WorkflowRuntimeAttestationIssuer(WorkflowRuntimeJson json, WorkflowRuntimeKeys keys, Clock clock) { this.json = json; this.keys = keys; this.clock = clock; }
    public void requireReady() { keys.signer(); }
    public Response sign(WorkflowRuntimeProofVerifier.Verified proof, WorkflowRuntimeIdentityPort.OwnerEvidence owner,
            JsonNode sourceVector, JsonNode result, Instant minimumExpiry) {
        long now = clock.instant().getEpochSecond(), expires = minimumExpiry.getEpochSecond();
        if (expires <= now || expires - now > 30 || minimumExpiry.isAfter(proof.expiresAt()) || minimumExpiry.isAfter(owner.expiresAt())) throw unavailable();
        WorkflowRuntimeResults.require(json, proof, result);
        String vectorSha = sha(json.canonical(sourceVector));
        var authority = new TreeMap<String, Object>();
        authority.put("ownerAuthRevision", owner.authRevision()); authority.put("ownerPolicyRevision", owner.policyRevision());
        authority.put("sourceVectorSha256", vectorSha);
        authority.put("sourceRevision", "awr-" + sha(proof.operation().name() + "\n" + proof.bindingsSha256() + "\n" + vectorSha));
        authority.put("evaluatedAt", now); authority.put("expiresAt", expires);
        var operation = proof.operation(); var claims = new TreeMap<String, Object>();
        claims.put("iss", operation.attestationIssuer()); claims.put("aud", operation.attestationAudience()); claims.put("purpose", operation.attestationPurpose());
        claims.put("sub", Long.toString(proof.caller().actorId())); claims.put("operation", operation.name());
        claims.put("iat", now); claims.put("nbf", now); claims.put("exp", expires); claims.put("jti", UUID.randomUUID().toString());
        claims.put("sourceProofJti", proof.sourceProofJti().toString()); claims.put("transportProofJti", proof.transportProofJti().toString());
        claims.put("bodySha256", proof.bodySha256()); claims.put("bindingsSha256", proof.bindingsSha256()); claims.put("authority", authority); claims.put("result", result);
        exact(json.tree(claims), ATTESTATION_CLAIMS); exact(json.tree(authority), AUTHORITY_FIELDS);
        try {
            var key = keys.signer();
            var jwt = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(), new Payload(json.canonical(json.tree(claims))));
            jwt.sign(new RSASSASigner(key)); String token = jwt.serialize();
            if (token.length() > MAX_ATTESTATION || json.canonical(json.tree(Map.of("attestation", token))).getBytes(StandardCharsets.UTF_8).length > MAX_BODY) throw unavailable();
            return new Response(token);
        } catch (com.dwp.core.exception.BaseException exception) { throw exception; }
        catch (Exception exception) { throw unavailable(); }
    }
    public record Response(String attestation) { }
}
