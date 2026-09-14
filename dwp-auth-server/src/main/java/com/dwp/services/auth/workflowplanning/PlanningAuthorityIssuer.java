package com.dwp.services.auth.workflowplanning;

import static com.dwp.services.auth.workflowplanning.PlanningProtocol.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import java.time.Clock;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/** Only the service's private verified Current factory can reach the signing operation. */
public final class PlanningAuthorityIssuer implements PlanningAuthorityService.SigningPort {
    private final PlanningJson json; private final Supplier<PlanningKeys> keys; private final Clock clock;
    public PlanningAuthorityIssuer(PlanningJson json,Supplier<PlanningKeys> keys,Clock clock) { this.json=json;this.keys=keys;this.clock=clock; }
    @Override
    public String issue(PlanningProofVerifier.Verified proof,PlanningAuthorityService.Current current) {
        long issued=current.evaluated().getEpochSecond(),exp=current.expires().getEpochSecond(),now=clock.instant().getEpochSecond();
        if(issued>now || exp<=now || exp<=issued || exp-issued>30 || current.expires().isAfter(proof.expiresAt())
                || !current.owner().authRevision().matches("auth-[a-f0-9]{64}") || !current.owner().policyRevision().matches("policy-10-[1-9][0-9]*-[a-f0-9]{64}")) throw unavailable();
        var claims=new TreeMap<String,Object>(); claims.put("iss",OWNER_AUDIENCE); claims.put("aud",OWNER_ISSUER);
        claims.put("sub",Long.toString(proof.bindings().actorId())); claims.put("iat",issued); claims.put("nbf",issued); claims.put("exp",exp);
        claims.put("jti",UUID.randomUUID().toString()); claims.put("purpose",ATTESTATION_PURPOSE); claims.put("operation",OPERATION);
        claims.put("sourceProofJti",proof.sourceJti()); claims.put("transportProofJti",proof.transportJti()); claims.put("bodySha256",proof.bodySha256()); claims.put("bindingsSha256",proof.bindingsSha256());
        claims.put("authority",Map.of("ownerAuthRevision",current.owner().authRevision(),"ownerPolicyRevision",current.owner().policyRevision(),
                "sourceRevision","awp-"+current.vector(),"sourceVectorSha256",current.vector(),"evaluatedAt",issued,"expiresAt",exp));
        claims.put("result",Map.of("snapshotSha256",proof.bindings().source().get("snapshotSha256"),"roles",current.roles().roles()));
        try {
            var key=keys.get().signer(); var jwt=new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),
                    new Payload(new String(json.bytes(claims),java.nio.charset.StandardCharsets.UTF_8))); jwt.sign(new RSASSASigner(key));
            String token=jwt.serialize(); if(token.length()>ATTESTATION_LIMIT) throw unavailable(); return token;
        } catch(Exception invalid) { throw unavailable(); }
    }
}
