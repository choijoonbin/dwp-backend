package com.dwp.services.auth.workflowplanning;

import static com.dwp.services.auth.workflowplanning.PlanningProtocol.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Owner-side material is fixture-only; this never represents current Auth or native database authority. */
public final class PlanningProofTestFixture {
    public final PlanningJson json=new PlanningJson();
    public final RSAKey owner=key("planning-test-owner"),transport=key("planning-test-transport"),attestation=key("planning-test-attestation");
    public PlanningKeys keys() { return new PlanningKeys(json,new JWKSet(owner.toPublicJWK()).toString(),new JWKSet(transport.toPublicJWK()).toString(),
            attestation.toJSONString(),new JWKSet(attestation.toPublicJWK()).toString(),List.of()); }
    public ObjectNode bindings(long tenant,long actor,UUID person,String context,String scope,long validUntil,int stageCount) {
        UUID workflow=UUID.randomUUID(),version=UUID.randomUUID(),form=UUID.randomUUID();
        var definition=object();definition.put("schemaContract","DWP_APPROVAL_WORKFLOW_QUORUM_V2");definition.put("schemaVersion",2);definition.put("slaMinutes",1000);
        var stages=definition.putArray("stages");var topology=json.tree(List.of()).deepCopy();var ordered=(com.fasterxml.jackson.databind.node.ArrayNode)topology;
        for(int index=0;index<stageCount;index++) {
            String step=String.format("STAGE_%02d",index);var stage=stages.addObject();stage.put("key",step);stage.put("name","Stage "+index);
            stage.put("candidateRole","PLANNING_APPROVER");stage.putObject("quorum").put("mode","ALL");stage.put("slaMinutes",15);
            var predecessors=stage.putArray("predecessors");if(index>0) predecessors.add(String.format("STAGE_%02d",index-1));
            var entry=ordered.addObject();entry.put("stepKey",step);entry.put("selected",true);entry.set("predecessors",predecessors.deepCopy());
        }
        String raw=new String(json.bytes(definition),StandardCharsets.UTF_8);var snapshot=object();
        snapshot.put("tenantId",tenant);snapshot.put("workflowId",workflow.toString());snapshot.put("workflowVersionId",version.toString());snapshot.put("formVersionId",form.toString());
        for(String prefix:List.of("workflow","form")) {snapshot.put(prefix+"_revision",0);snapshot.put(prefix+"_head",1);snapshot.put(prefix+"_version",1);
            snapshot.put(prefix+"_parent_state","DRAFT");snapshot.put(prefix+"_state","DRAFT");}
        snapshot.put("formId",UUID.randomUUID().toString());snapshot.put("workflowDefinitionSha256",PlanningJson.sha(raw));snapshot.put("formSchemaSha256","a".repeat(64));
        snapshot.put("managementResourceSetKey","RS_APPROVALS");snapshot.put("policyVersion",1);var refs=snapshot.putArray("policyReferences");
        for(String policy:List.of("BLOCK_SELF_APPROVAL","REQUIRE_REJECT_REASON","SLA_ESCALATION")) {
            var ref=refs.addObject();ref.put("policyId",UUID.randomUUID().toString());ref.put("key",policy);ref.put("rowVersion",0);ref.put("enforcement","ENFORCED");ref.putObject("rule");
        }
        snapshot.put("policySha256",json.digest(Map.of("references",refs)));var sample=snapshot.putObject("samplePayload");sample.put("value",1);
        snapshot.put("samplePayloadSha256",json.digest(sample));snapshot.putNull("effectiveFrom");snapshot.putNull("effectiveTo");
        var binding=object();var source=binding.putObject("source");source.set("snapshot",snapshot);source.put("snapshotSha256",json.digest(snapshot));
        source.put("definition",raw);source.putArray("roleCodes").add("PLANNING_APPROVER");source.set("topology",topology);
        var current=binding.putObject("owner");current.put("tenantId",tenant);current.put("actorId",actor);current.put("personPublicId",person.toString());
        current.put("contextKey",context);current.put("contextScopeKey",scope);current.put("decisionRevision","psr-fixture-not-auth-revision");current.put("routeContractKey",ROUTE);
        current.put("method","POST");current.put("path","/v1/admin/workflows/"+workflow+"/versions/"+version+"/simulation");current.put("accessMode","NORMAL");
        current.put("rolloutState","111");current.put("authorityValidUntil",validUntil);current.put("managementResourceSetKey","RS_APPROVALS");
        current.put("workflowId",workflow.toString());current.put("workflowVersionId",version.toString());current.put("formVersionId",form.toString());current.put("sourceSnapshotSha256",json.digest(snapshot));
        return binding;
    }
    public Exchange issue(ObjectNode bindings) { return issue(bindings,claims->{},claims->{}); }
    public Exchange issue(ObjectNode bindings,Consumer<ObjectNode> ownerChange,Consumer<ObjectNode> transportChange) {
        long now=Instant.now().getEpochSecond(),exp=Math.min(now+30,bindings.at("/owner/authorityValidUntil").longValue());
        var ownerClaims=standard(OWNER_ISSUER,OWNER_AUDIENCE,OWNER_PURPOSE,bindings.at("/owner/actorId").asText(),now,exp);
        ownerClaims.put("version",1);ownerClaims.set("sealed",bindings.deepCopy());ownerChange.accept(ownerClaims);String source=sign(owner,ownerClaims);
        var envelope=object();envelope.put("operation",OPERATION);envelope.put("sourceProof",source);envelope.set("bindings",bindings.deepCopy());byte[] bytes=json.bytes(envelope);
        var transit=standard(TRANSPORT_ISSUER,TRANSPORT_AUDIENCE,TRANSPORT_PURPOSE,bindings.at("/owner/actorId").asText(),now,exp);
        transit.set("sourceProofJti",ownerClaims.get("jti"));transit.put("sourceProofSha256",PlanningJson.sha(source));transit.put("bodySha256",PlanningJson.sha(bytes));
        transit.set("contextKey",bindings.at("/owner/contextKey"));transit.put("routeContractKey",ROUTE);transportChange.accept(transit);
        return new Exchange(bytes,sign(transport,transit));
    }
    public PlanningProofVerifier verifier() { return new PlanningProofVerifier(json,keys(),Clock.systemUTC()); }
    public ObjectNode object() { return (ObjectNode)json.tree(Map.of()); }
    private ObjectNode standard(String issuer,String audience,String purpose,String subject,long now,long expires) {
        var claims=object();claims.put("iss",issuer);claims.put("aud",audience);claims.put("purpose",purpose);claims.put("sub",subject);
        claims.put("iat",now);claims.put("nbf",now);claims.put("exp",expires);claims.put("jti",UUID.randomUUID().toString());return claims;
    }
    public String sign(RSAKey key,ObjectNode claims) {
        try {var jwt=new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),new Payload(new String(json.bytes(claims),StandardCharsets.UTF_8)));
            jwt.sign(new RSASSASigner(key));return jwt.serialize();} catch(Exception error) {throw new AssertionError(error);}
    }
    public static RSAKey key(String id) {
        try {return new RSAKeyGenerator(2048).keyID(id).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();} catch(Exception error) {throw new AssertionError(error);}
    }
    public record Exchange(byte[] body,String transport) {
        public Exchange {body=body.clone();}
        @Override public byte[] body() {return body.clone();}
    }
}
