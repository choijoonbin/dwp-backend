package com.dwp.services.approval.workflowauthority;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;

public final class WorkflowRuntimeAttestationVerifier {
    private final WorkflowRuntimeKeys keys;
    private final WorkflowRuntimeJson json;
    private final Clock clock;
    public static final class Verified {
        private final String token;
        private final JsonNode authority,result,bindings;
        private final Operation operation;
        private Verified(String token, JsonNode authority, JsonNode result, WorkflowRuntimeProofIssuer.Exchange exchange) {
            this.token=token;this.authority=authority.deepCopy();this.result=result.deepCopy();this.bindings=exchange.bindings();this.operation=exchange.operation();
        }
        public String token() { return token; }
        public JsonNode authority() { return authority.deepCopy(); }
        public JsonNode result() { return result.deepCopy(); }
        public JsonNode bindings() { return bindings.deepCopy(); }
        public Operation operation() { return operation; }
        public Instant expiresAt() { return Instant.ofEpochSecond(authority.get("expiresAt").longValue()); }
    }
    public WorkflowRuntimeAttestationVerifier(WorkflowRuntimeKeys keys, WorkflowRuntimeJson json, Clock clock) { this.keys=keys;this.json=json;this.clock=clock; }
    public Verified verify(byte[] response, WorkflowRuntimeProofIssuer.Exchange exchange) {
        WorkflowRuntimeBindingContract.validate(exchange.operation(),exchange.bindings());
        var envelope=json.parse(response,BODY_MAX); exact(envelope,Set.of("attestation"));
        String token=text(envelope,"attestation",ATTESTATION_MAX); String[] parts=token.split("\\.",-1);
        if (parts.length!=3) throw denied();
        var header=json.parse(part(parts[0]),2048);exact(header,Set.of("alg","typ","kid"));
        if (!"RS256".equals(text(header,"alg",5)) || !"JWT".equals(text(header,"typ",3))) throw denied();
        var key=keys.attestations.get(text(header,"kid",80));part(parts[2]);
        try { if (key==null || !SignedJWT.parse(token).verify(new RSASSAVerifier(key))) throw denied(); }
        catch (com.dwp.core.exception.BaseException error) { throw error; } catch (Exception error) { throw denied(); }
        var claims=json.parse(part(parts[1]),BODY_MAX);exact(claims,ATTESTATION_CLAIMS);var op=exchange.operation();
        if (!op.attestationIssuer().equals(text(claims,"iss",160)) || !op.attestationAudience().equals(text(claims,"aud",160))
                || !op.attestationPurpose().equals(text(claims,"purpose",100)) || !op.name().equals(text(claims,"operation",40))
                || !exchange.actorId().equals(text(claims,"sub",20)) || !exchange.ownerJti().equals(uuid(claims,"sourceProofJti"))
                || !exchange.transportJti().equals(uuid(claims,"transportProofJti"))
                || !exchange.bodySha256().equals(hash(claims,"bodySha256")) || !exchange.bindingsSha256().equals(hash(claims,"bindingsSha256"))) throw denied();
        uuid(claims,"jti"); long issued=integer(claims,"iat",1), starts=integer(claims,"nbf",1), expires=integer(claims,"exp",1), now=clock.instant().getEpochSecond();
        if (issued>now || starts!=issued || expires<=now || expires<=issued || expires-issued>30 || expires>exchange.expiresAt().getEpochSecond()) throw denied();
        var authority=claims.get("authority"); exact(authority,AUTHORITY_FIELDS);
        text(authority,"ownerAuthRevision",200);text(authority,"ownerPolicyRevision",200);hash(authority,"sourceVectorSha256");
        if (!text(authority,"sourceRevision",68).matches("awr-[a-f0-9]{64}") || integer(authority,"evaluatedAt",1)<issued
                || integer(authority,"evaluatedAt",1)>now || integer(authority,"expiresAt",1)!=expires) throw denied();
        var result=claims.get("result");
        switch (op) {
            case INFORMATION_ADMISSION -> information(result,exchange.bindings());
            case CANDIDATES -> candidates(result,exchange.bindings());
            case VOTER -> voter(result,exchange.bindings());
        }
        return new Verified(token,authority,result,exchange);
    }
    private void information(JsonNode result, JsonNode bindings) {
        exact(bindings,Set.of("command"));var command=bindings.get("command");exact(command,COMMAND_FIELDS);exact(result,INFO_RESULT_FIELDS);
        for (String key : INFO_RESULT_FIELDS) if (!same(result.get(key),command.get(key))) throw denied();
        integer(result,"tenantId",1);uuid(result,"personPublicId");
    }
    private void candidates(JsonNode result, JsonNode bindings) {
        exact(result,Set.of("role","complete","truncated","members","memberSetSha256"));
        if (!result.get("complete").isBoolean() || !result.get("complete").booleanValue()
                || !result.get("truncated").isBoolean() || result.get("truncated").booleanValue()) throw denied();
        var role=result.get("role");exact(role,Set.of("roleCode","roleId","roleVersion"));
        if (!text(role,"roleCode",100).equals(text(bindings.get("stage"),"candidateRole",100))) throw denied();
        long roleId=integer(role,"roleId",1);integer(role,"roleVersion",0);
        var members=result.get("members");if (!members.isArray() || members.size()>1000) throw denied();
        long last=0;var people=new java.util.HashSet<java.util.UUID>();
        for (var member:members) {
            subject(member,integer(bindings.get("owner"),"tenantId",1),roleId,true);
            long id=integer(member,"userId",1);if (id<=last || !people.add(uuid(member,"personPublicId"))) throw denied();last=id;
        }
        if (!sha(json.bytes(members)).equals(hash(result,"memberSetSha256"))) throw denied();
    }
    private void voter(JsonNode result, JsonNode bindings) {
        exact(result,Set.of("target","actor","principal","delegation","ownerAdmissionDigest"));
        var target=bindings.get("target");if (target==null || !same(target,result.get("target")) || !same(target.get("delegation"),result.get("delegation"))) throw denied();
        long tenant=integer(bindings.get("owner"),"tenantId",1);
        subject(result.get("actor"),tenant,0,false);subject(result.get("principal"),tenant,0,true);
        if (integer(result.get("actor"),"userId",1)!=integer(target,"actorId",1)
                || !uuid(result.get("actor"),"personPublicId").equals(uuid(target,"actorPersonPublicId"))
                || integer(result.get("principal"),"userId",1)!=integer(target,"principalId",1)
                || !uuid(result.get("principal"),"personPublicId").equals(uuid(target,"principalPersonPublicId"))
                || !result.get("ownerAdmissionDigest").equals(bindings.get("stage").get("admissionAttestationSha256"))) throw denied();
    }
    private void subject(JsonNode value,long tenant,long role,boolean principal) {
        exact(value,Set.of("tenantId","userId","personPublicId","identityPlane","status","roleIds","canApprove"));
        if (integer(value,"tenantId",1)!=tenant || !"TENANT".equals(text(value,"identityPlane",20)) || !"ACTIVE".equals(text(value,"status",20))
                || !value.get("canApprove").isBoolean() || !value.get("canApprove").booleanValue()) throw denied();
        integer(value,"userId",1);uuid(value,"personPublicId");var roles=value.get("roleIds");
        if (!roles.isArray() || roles.size()>1 || principal && roles.size()!=1) throw denied();
        if (roles.size()==1 && (!roles.get(0).isIntegralNumber() || !roles.get(0).canConvertToLong() || roles.get(0).longValue()<1
                || role>0 && roles.get(0).longValue()!=role)) throw denied();
    }
    private boolean same(JsonNode left,JsonNode right) {
        return left!=null && right!=null && java.util.Arrays.equals(json.bytes(left),json.bytes(right));
    }
}
