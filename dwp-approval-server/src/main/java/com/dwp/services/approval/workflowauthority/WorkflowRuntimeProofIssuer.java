package com.dwp.services.approval.workflowauthority;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

public final class WorkflowRuntimeProofIssuer {
    private final WorkflowRuntimeKeys keys;
    private final WorkflowRuntimeJson json;
    private final Clock clock;
    public record Exchange(Operation operation, String actorId, UUID ownerJti, UUID transportJti,
            String bodySha256, String bindingsSha256, Instant expiresAt, JsonNode bindings, byte[] body, String transportToken) {
        public Exchange { bindings=bindings.deepCopy(); body=body.clone(); }
        @Override public JsonNode bindings() { return bindings.deepCopy(); }
        @Override public byte[] body() { return body.clone(); }
    }
    public WorkflowRuntimeProofIssuer(WorkflowRuntimeKeys keys, WorkflowRuntimeJson json, Clock clock) { this.keys=keys;this.json=json;this.clock=clock; }
    public Exchange issue(Operation operation, long actorId, JsonNode bindings, Instant authorityExpiry) {
        if (operation==null || actorId<1 || authorityExpiry==null) throw denied();
        long issued=clock.instant().getEpochSecond(), expires=Math.min(issued+30,authorityExpiry.getEpochSecond());
        if (expires<=issued) throw unavailable();
        var frozen=json.sorted(bindings); String actor=Long.toString(actorId);
        UUID ownerId=UUID.randomUUID(), transportId=UUID.randomUUID();
        var owner=common(operation.ownerIssuer(),operation.ownerAudience(),actor,operation.ownerPurpose(),operation,ownerId,issued,expires)
                .claim("bindings", new com.fasterxml.jackson.databind.ObjectMapper().convertValue(frozen,Object.class)).build();
        String source=sign(owner,keys.owner); if (source.length()>OWNER_MAX) throw denied();
        var body=json.object(); body.put("operation",operation.name());body.put("sourceProof",source);body.set("bindings",frozen);
        byte[] bytes=json.bytes(body); if (bytes.length>BODY_MAX) throw denied();
        String digest=WorkflowRuntimeJson.sha(bytes);
        var transport=common(TRANSPORT_ISSUER,TRANSPORT_AUDIENCE,actor,TRANSPORT_PURPOSE,operation,transportId,issued,expires)
                .claim("httpMethod","POST").claim("httpPath",PATH).claim("bodySha256",digest).claim("sourceProofSha256",WorkflowRuntimeJson.sha(source)).build();
        String token=sign(transport,keys.transport); if (token.length()>TRANSPORT_MAX) throw denied();
        return new Exchange(operation,actor,ownerId,transportId,digest,WorkflowRuntimeJson.sha(json.bytes(frozen)),Instant.ofEpochSecond(expires),frozen,bytes,token);
    }
    private static JWTClaimsSet.Builder common(String issuer,String audience,String actor,String purpose,Operation operation,UUID id,long issued,long expires) {
        return new JWTClaimsSet.Builder().issuer(issuer).claim("aud",audience).subject(actor).issueTime(new Date(issued*1000))
                .notBeforeTime(new Date(issued*1000)).expirationTime(new Date(expires*1000)).jwtID(id.toString()).claim("purpose",purpose).claim("operation",operation.name());
    }
    private static String sign(JWTClaimsSet claims, RSAKey key) {
        try { var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),claims);
            jwt.sign(new RSASSASigner(key));return jwt.serialize(); } catch (Exception error) { throw unavailable(); }
    }
}
