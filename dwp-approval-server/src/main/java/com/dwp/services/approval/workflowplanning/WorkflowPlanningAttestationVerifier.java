package com.dwp.services.approval.workflowplanning;

import static com.dwp.services.approval.workflowplanning.WorkflowPlanningProtocol.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.denied;

import com.dwp.services.approval.domain.ApprovalWorkflowStudioSource;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;

public final class WorkflowPlanningAttestationVerifier {
    public static final class Verified {
        private final JsonNode authority,result;
        private final String bindingsSha;
        private final Instant expires;
        private Verified(JsonNode authority,JsonNode result,String bindingsSha,Instant expires) {
            this.authority=authority.deepCopy();this.result=result.deepCopy();this.bindingsSha=bindingsSha;this.expires=expires;
        }
        public void requireSameCurrent(Verified other) {
            var json=new WorkflowRuntimeJson();
            if(other==null || !bindingsSha.equals(other.bindingsSha) || !authority.get("ownerAuthRevision").equals(other.authority.get("ownerAuthRevision"))
                    || !authority.get("ownerPolicyRevision").equals(other.authority.get("ownerPolicyRevision"))
                    || !authority.get("sourceVectorSha256").equals(other.authority.get("sourceVectorSha256")) || !Arrays.equals(json.bytes(result),json.bytes(other.result))) throw denied();
        }
        public WorkflowPlanningResult result(ApprovalWorkflowStudioSource.Snapshot snapshot) {
            if(snapshot==null || !snapshot.sha256().equals(result.get("snapshotSha256").textValue()) || !expires.isAfter(Instant.now())) throw denied();
            var counts=new HashMap<String,Integer>();result.get("roles").forEach(role->counts.put(role.get("roleCode").textValue(),role.get("activeMemberCount").intValue()));
            var paths=new HashMap<String,ApprovalWorkflowStudioSource.StagePath>();snapshot.topology().forEach(path->paths.put(path.stepKey(),path));
            var stages=new ArrayList<WorkflowPlanningResult.Stage>();
            for(var stage:snapshot.definition().topologicalStages()) {
                Integer count=counts.get(stage.candidateRole());if(count==null) throw denied();var path=paths.get(stage.key());Integer threshold=null;
                String warning=count==0?"EMPTY_POOL":stage.quorum().mode()==com.dwp.services.approval.domain.ApprovalWorkflowQuorum.Mode.COUNT && stage.quorum().value()>count?"INSUFFICIENT_POOL":null;
                if(warning==null) threshold=stage.quorum().threshold(count);
                stages.add(new WorkflowPlanningResult.Stage(stage.key(),path.selected(),path.predecessors(),stage.candidateRole(),stage.quorum().mode().name(),stage.quorum().value(),count,threshold,warning));
            }
            return new WorkflowPlanningResult("ROLE_POOL_PREVIEW","NOT_EVALUATED","NOT_EVALUATED",snapshot.sha256(),authority.get("sourceRevision").textValue(),expires,stages);
        }
    }
    private final WorkflowPlanningKeys keys;
    private final Clock clock;
    private final WorkflowRuntimeJson json=new WorkflowRuntimeJson();
    public WorkflowPlanningAttestationVerifier(WorkflowPlanningKeys keys,Clock clock) {this.keys=keys;this.clock=clock;}
    public Verified verify(byte[] raw,WorkflowPlanningProofIssuer.Exchange exchange) {
        if(exchange==null) throw denied();var envelope=json.parse(raw,BODY_MAX);exact(envelope,Set.of("attestation"));String token=text(envelope,"attestation",ATTESTATION_MAX);
        var parts=token.split("\\.",-1);if(parts.length!=3) throw denied();var header=json.parse(part(parts[0]),2048);exact(header,Set.of("alg","typ","kid"));
        if(!"RS256".equals(text(header,"alg",5)) || !"JWT".equals(text(header,"typ",3))) throw denied();var signer=keys.attestations.get(text(header,"kid",80));part(parts[2]);
        try {if(signer==null || !SignedJWT.parse(token).verify(new RSASSAVerifier(signer))) throw denied();}
        catch(com.dwp.core.exception.BaseException error) {throw error;} catch(Exception invalid) {throw denied();}
        var claims=json.parse(part(parts[1]),BODY_MAX);exact(claims,ATTESTATION_FIELDS);
        if(!OWNER_AUDIENCE.equals(text(claims,"iss",160)) || !OWNER_ISSUER.equals(text(claims,"aud",160)) || !ATTESTATION_PURPOSE.equals(text(claims,"purpose",100))
                || !OPERATION.equals(text(claims,"operation",40)) || !exchange.actor.equals(text(claims,"sub",20)) || !exchange.sourceJti.equals(uuid(claims,"sourceProofJti"))
                || !exchange.transportJti.equals(uuid(claims,"transportProofJti")) || !exchange.bodySha.equals(hash(claims,"bodySha256")) || !exchange.bindingsSha.equals(hash(claims,"bindingsSha256"))) throw denied();
        uuid(claims,"jti");long issued=integer(claims,"iat",1),starts=integer(claims,"nbf",1),expires=integer(claims,"exp",1),now=clock.instant().getEpochSecond();
        if(issued>now || starts!=issued || expires<=now || expires<=issued || expires-issued>30 || expires>exchange.expires.getEpochSecond()) throw denied();
        var authority=claims.get("authority");exact(authority,AUTHORITY_FIELDS);text(authority,"ownerAuthRevision",200);text(authority,"ownerPolicyRevision",200);
        if(!text(authority,"sourceRevision",68).equals("awp-"+hash(authority,"sourceVectorSha256")) || integer(authority,"evaluatedAt",1)<issued
                || integer(authority,"evaluatedAt",1)>now || integer(authority,"expiresAt",1)!=expires) throw denied();
        var result=claims.get("result");exact(result,Set.of("snapshotSha256","roles"));var source=exchange.bindings().get("source");
        if(!hash(result,"snapshotSha256").equals(hash(source,"snapshotSha256"))) throw denied();var roles=result.get("roles");var expected=source.get("roleCodes");
        if(!roles.isArray() || roles.size()!=expected.size()) throw denied();var ids=new java.util.HashSet<Long>();
        for(int index=0;index<roles.size();index++) {
            var role=roles.get(index);exact(role,Set.of("roleCode","roleId","roleVersion","activeMemberCount"));
            if(!text(role,"roleCode",50).equals(expected.get(index).textValue()) || !ids.add(integer(role,"roleId",1)) || integer(role,"activeMemberCount",0)>1000) throw denied();integer(role,"roleVersion",0);
        }
        return new Verified(authority,result,exchange.bindingsSha,Instant.ofEpochSecond(expires));
    }
}
