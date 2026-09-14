package com.dwp.services.approval.workflowplanning;

import static com.dwp.services.approval.workflowplanning.WorkflowPlanningProtocol.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.denied;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.unavailable;

import com.dwp.services.approval.domain.ApprovalWorkflowStudioSource;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class WorkflowPlanningProofIssuer {
    public static final class Exchange {
        final UUID sourceJti,transportJti;
        final String actor,bodySha,bindingsSha,token;
        final Instant expires;
        private final JsonNode bindings;
        private final byte[] body;
        private Exchange(UUID sourceJti,UUID transportJti,String actor,String bodySha,String bindingsSha,String token,Instant expires,JsonNode bindings,byte[] body) {
            this.sourceJti=sourceJti;this.transportJti=transportJti;this.actor=actor;this.bodySha=bodySha;this.bindingsSha=bindingsSha;this.token=token;this.expires=expires;
            this.bindings=bindings.deepCopy();this.body=body.clone();
        }
        public JsonNode bindings() {return bindings.deepCopy();}
        public byte[] body() {return body.clone();}
        public String token() {return token;}
        public Instant expiresAt() {return expires;}
    }
    private final WorkflowPlanningKeys keys;
    private final Clock clock;
    private final WorkflowRuntimeJson json=new WorkflowRuntimeJson();
    public WorkflowPlanningProofIssuer(WorkflowPlanningKeys keys,Clock clock) {this.keys=keys;this.clock=clock;}
    public Exchange issue(WorkflowPlanningInstalledContext.Seal context,ApprovalWorkflowStudioSource.Snapshot snapshot) {
        if(context==null || snapshot==null || !context.actor.equals(ApprovalRequestContext.require())) throw denied();
        var material=json.parse(snapshot.canonicalJson().getBytes(StandardCharsets.UTF_8),BODY_MAX);var actor=context.actor;
        if(integer(material,"tenantId",1)!=actor.tenantId() || !uuid(material,"workflowId").equals(context.workflowId)
                || !uuid(material,"workflowVersionId").equals(context.versionId) || !text(material,"managementResourceSetKey",80).equals(context.scope.resourceSetKey())) throw denied();
        long now=clock.instant().getEpochSecond(),expires=Math.min(now+30,context.evidence.validUntil().toInstant().getEpochSecond());
        if(snapshot.authorityDeadline()!=null) expires=Math.min(expires,snapshot.authorityDeadline().getEpochSecond());
        if(expires<=now) throw unavailable();
        var owner=json.object();owner.put("tenantId",actor.tenantId());owner.put("actorId",actor.userId());owner.put("personPublicId",actor.personPublicId().toString());
        owner.put("contextKey",context.evidence.contextKey());owner.put("contextScopeKey",context.evidence.contextScopeKey());owner.put("decisionRevision",context.evidence.revision());
        owner.put("routeContractKey",ROUTE);owner.put("method","POST");owner.put("path",context.path);owner.put("accessMode",context.mode);
        owner.put("rolloutState",context.evidence.rolloutState());owner.put("authorityValidUntil",context.evidence.validUntil().toInstant().getEpochSecond());
        owner.put("managementResourceSetKey",context.scope.resourceSetKey());owner.put("workflowId",context.workflowId.toString());owner.put("workflowVersionId",context.versionId.toString());
        owner.set("formVersionId",material.get("formVersionId"));owner.put("sourceSnapshotSha256",snapshot.sha256());
        var source=json.object();source.set("snapshot",material);source.put("snapshotSha256",snapshot.sha256());source.put("definition",snapshot.definition().canonicalJson());
        source.set("roleCodes",json.tree(snapshot.definition().stages().stream().map(stage->stage.candidateRole()).distinct().sorted().toList()));source.set("topology",json.tree(snapshot.topology()));
        var bindings=json.object();bindings.set("owner",owner);bindings.set("source",source);
        UUID sourceJti=UUID.randomUUID(),transportJti=UUID.randomUUID();String subject=Long.toString(actor.userId());
        var claims=common(OWNER_ISSUER,OWNER_AUDIENCE,OWNER_PURPOSE,subject,sourceJti,now,expires);claims.put("version",1);claims.put("sealed",bindings);
        String sourceProof=sign(json.tree(claims),keys.owner);if(sourceProof.length()>OWNER_MAX) throw denied();
        var envelope=json.object();envelope.put("operation",OPERATION);envelope.put("sourceProof",sourceProof);envelope.set("bindings",bindings);byte[] body=json.bytes(envelope);
        if(body.length>BODY_MAX) throw denied();String bodySha=sha(body);var transport=common(TRANSPORT_ISSUER,TRANSPORT_AUDIENCE,TRANSPORT_PURPOSE,subject,transportJti,now,expires);
        transport.put("sourceProofJti",sourceJti.toString());transport.put("sourceProofSha256",sha(sourceProof));transport.put("bodySha256",bodySha);
        transport.put("contextKey",context.evidence.contextKey());transport.put("routeContractKey",ROUTE);String token=sign(json.tree(transport),keys.transport);if(token.length()>TOKEN_MAX) throw denied();
        return new Exchange(sourceJti,transportJti,subject,bodySha,sha(json.bytes(bindings)),token,Instant.ofEpochSecond(expires),bindings,body);
    }
    private static Map<String,Object> common(String issuer,String audience,String purpose,String subject,UUID jti,long now,long expires) {
        return new TreeMap<>(Map.of("iss",issuer,"aud",audience,"purpose",purpose,"sub",subject,"iat",now,"nbf",now,"exp",expires,"jti",jti.toString()));
    }
    private String sign(JsonNode claims,RSAKey key) {
        try {var jwt=new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),new Payload(new String(json.bytes(claims),StandardCharsets.UTF_8)));
            jwt.sign(new RSASSASigner(key));return jwt.serialize();} catch(Exception invalid) {throw unavailable();}
    }
}
