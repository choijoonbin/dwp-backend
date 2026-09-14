package com.dwp.services.approval.informationreplay;

import static com.dwp.services.approval.informationreplay.InformationReplayProtocol.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.denied;

import com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class InformationReplayAttestationVerifier {
    public static final class Verified {
        private final JsonNode authority,result;
        private final Instant expires;
        private final String bindingsSha;
        private Verified(JsonNode authority,JsonNode result,Instant expires,String bindingsSha) {
            this.authority=authority.deepCopy();this.result=result.deepCopy();this.expires=expires;this.bindingsSha=bindingsSha;
        }
        public Instant expiresAt() {return expires;}
        public void requireSameCurrent(Verified other) {
            var json=new WorkflowRuntimeJson();
            if(other==null || !bindingsSha.equals(other.bindingsSha) || !authority.get("ownerAuthRevision").equals(other.authority.get("ownerAuthRevision"))
                    || !authority.get("ownerPolicyRevision").equals(other.authority.get("ownerPolicyRevision"))
                    || !authority.get("sourceVectorSha256").equals(other.authority.get("sourceVectorSha256"))
                    || !java.util.Arrays.equals(json.bytes(result),json.bytes(other.result))) throw denied();
        }
    }
    private final InformationReplayKeys keys;
    private final Clock clock;
    private final WorkflowRuntimeJson json=new WorkflowRuntimeJson();
    public InformationReplayAttestationVerifier(InformationReplayKeys keys,Clock clock) {this.keys=keys;this.clock=clock;}
    public Verified verify(byte[] body,InformationReplayProofIssuer.Exchange exchange) {
        if(exchange==null) throw denied();var envelope=json.parse(body,BODY_MAX);exact(envelope,Set.of("attestation"));
        String token=text(envelope,"attestation",ATTESTATION_MAX);var parts=token.split("\\.",-1);if(parts.length!=3) throw denied();
        var header=json.parse(part(parts[0]),2048);exact(header,Set.of("alg","typ","kid"));
        if(!text(header,"alg",5).equals("RS256") || !text(header,"typ",3).equals("JWT")) throw denied();
        var signer=keys.attestations.get(text(header,"kid",80));part(parts[2]);
        try {if(signer==null || !SignedJWT.parse(token).verify(new RSASSAVerifier(signer))) throw denied();}
        catch(com.dwp.core.exception.BaseException error) {throw error;} catch(Exception error) {throw denied();}
        var claims=json.parse(part(parts[1]),BODY_MAX);exact(claims,ATTESTATION_FIELDS);
        if(!text(claims,"iss",160).equals(OWNER_AUDIENCE) || !text(claims,"aud",160).equals(OWNER_ISSUER)
                || !text(claims,"purpose",100).equals(ATTESTATION_PURPOSE) || !text(claims,"operation",40).equals(OPERATION)
                || !text(claims,"sub",20).equals(InformationReplayProofIssuer.actor(exchange))
                || !uuid(claims,"sourceProofJti").equals(InformationReplayProofIssuer.sourceJti(exchange))
                || !uuid(claims,"transportProofJti").equals(InformationReplayProofIssuer.transportJti(exchange))
                || !hash(claims,"bodySha256").equals(InformationReplayProofIssuer.bodySha(exchange))
                || !hash(claims,"bindingsSha256").equals(InformationReplayProofIssuer.bindingsSha(exchange))) throw denied();
        uuid(claims,"jti");long issued=integer(claims,"iat",1),starts=integer(claims,"nbf",1),expires=integer(claims,"exp",1),now=clock.instant().getEpochSecond();
        if(issued>now || starts!=issued || expires<=now || expires<=issued || expires-issued>30 || expires>exchange.expiresAt().getEpochSecond()) throw denied();
        var authority=claims.get("authority");exact(authority,AUTHORITY_FIELDS);text(authority,"ownerAuthRevision",200);text(authority,"ownerPolicyRevision",200);
        if(!text(authority,"sourceRevision",68).equals("air-"+hash(authority,"sourceVectorSha256")) || integer(authority,"evaluatedAt",1)<issued
                || integer(authority,"evaluatedAt",1)>now || integer(authority,"expiresAt",1)!=expires) throw denied();
        var result=claims.get("result");exact(result,Set.of("receiptSha256","commandSha256","admissionSha256","role","originalActor","principal"));
        var bindings=exchange.bindings();var admission=bindings.get("admission");var source=bindings.get("source");
        var target=bindings.get("target");var owner=bindings.get("owner");
        for(String field:Set.of("receiptSha256","commandSha256","admissionSha256")) if(!hash(result,field).equals(hash(admission,field))) throw denied();
        var role=result.get("role");exact(role,Set.of("roleCode","roleId","roleVersion"));
        if(!text(role,"roleCode",50).equals(text(source,"candidateRole",50))) throw denied();long roleId=integer(role,"roleId",1);integer(role,"roleVersion",0);
        subject(result.get("originalActor"),integer(owner,"tenantId",1),integer(target,"actorId",1),uuid(target,"actorPersonPublicId"),roleId,false);
        subject(result.get("principal"),integer(owner,"tenantId",1),integer(target,"principalId",1),uuid(target,"principalPersonPublicId"),roleId,true);
        return new Verified(authority,result,Instant.ofEpochSecond(expires),InformationReplayProofIssuer.bindingsSha(exchange));
    }
    private static void subject(JsonNode value,long tenant,long user,UUID person,long role,boolean mandatory) {
        exact(value,Set.of("tenantId","userId","personPublicId","identityPlane","status","roleIds","canApprove"));
        if(integer(value,"tenantId",1)!=tenant || integer(value,"userId",1)!=user || !uuid(value,"personPublicId").equals(person)
                || !text(value,"identityPlane",10).equals("TENANT") || !text(value,"status",10).equals("ACTIVE")
                || !value.get("canApprove").isBoolean() || !value.get("canApprove").booleanValue()) throw denied();
        var roles=value.get("roleIds");
        if(!roles.isArray() || roles.size()>1 || mandatory && roles.size()!=1 || roles.size()==1
                && (!roles.get(0).isIntegralNumber() || !roles.get(0).canConvertToLong() || roles.get(0).longValue()!=role)) throw denied();
    }
}
