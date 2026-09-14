package com.dwp.services.approval.domain;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.denied;

import com.dwp.services.approval.informationreplay.InformationReceiptBody;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reconstructs the original public DTO with server-owned immutable pins, not a caller's internal tuple. */
final class ApprovalInformationReceiptCommand {
    record Expected(long generation,long stageVersion,ApprovalWorkflowQuorum.Pins pins,int payloadRevision,String payloadSha256,
            Long expectedRequestVersion) implements ApprovalWorkflowQuorumExpectedVoteView { }
    static String digest(ApprovalWorkflowQuorumRuntimeStore store,Actor actor,UUID request,String key,InformationReceiptBody lookup,
            JsonNode admission,ApprovalWorkflowQuorumRuntimeStore.Context original) {
        var json=new WorkflowRuntimeJson();byte[] bytes=lookup.originalBytes();
        if(!sha(bytes).equals(hash(admission,"rawBodySha256")) || !lookup.operation().equals(text(admission,"operation",16))) throw denied();
        JsonNode raw=json.parse(bytes,InformationReceiptBody.ORIGINAL_MAX);Object command;
        if(lookup.operation().equals("REQUEST_INFO")) {
            exact(raw,Set.of("decision","comment","expectedVersion","quorum"));
            if(!text(raw,"decision",20).equals("REQUEST_INFO") || integer(raw,"expectedVersion",0)!=integer(admission,"originalExpectedVersion",0)) throw denied();
            var expected=raw.get("quorum");exact(expected,Set.of("generation","expectedStageVersion","pins","payloadRevision","payloadSha256","expectedRequestVersion"));
            var pins=expected.get("pins");exact(pins,Set.of("workflowVersionId","workflowVersion","workflowDefinitionSha256","formSchemaSha256","policyVersion","policySha256"));
            var actual=original.pins();
            if(!uuid(pins,"workflowVersionId").equals(actual.workflowVersionId()) || integer(pins,"workflowVersion",1)!=actual.workflowVersion()
                    || !hash(pins,"workflowDefinitionSha256").equals(actual.workflowDefinitionSha256()) || !hash(pins,"formSchemaSha256").equals(actual.formSchemaSha256())
                    || integer(pins,"policyVersion",1)!=actual.policyVersion() || !hash(pins,"policySha256").equals(actual.policySha256())
                    || integer(expected,"generation",1)!=integer(admission,"sourceGeneration",1) || integer(expected,"payloadRevision",1)!=original.payloadRevision()
                    || !hash(expected,"payloadSha256").equals(original.payloadSha256())) throw denied();
            long version=integer(expected,"expectedRequestVersion",0);
            if(version!=integer(admission,"receiptRequestVersion",1)-1) throw denied();
            var view=new Expected(integer(expected,"generation",1),integer(expected,"expectedStageVersion",1),actual,
                    original.payloadRevision(),original.payloadSha256(),version);
            command=new ApprovalWorkflowQuorumInformationRuntime.RequestCommand(request,uuid(admission,"taskId"),integer(raw,"expectedVersion",0),
                    version,view,key,text(raw,"comment",2000),sha(bytes));
        } else {
            var keys=new java.util.HashSet<String>();raw.fieldNames().forEachRemaining(keys::add);
            if(!keys.equals(Set.of("message","payload","expectedVersion","sourceGeneration"))
                    && !keys.equals(Set.of("message","expectedVersion","sourceGeneration"))) throw denied();
            long version=integer(raw,"expectedVersion",0),generation=integer(raw,"sourceGeneration",1);
            if(version!=integer(admission,"originalExpectedVersion",0) || version!=integer(admission,"receiptRequestVersion",1)-1
                    || generation!=integer(admission,"sourceGeneration",1)) throw denied();
            Map<String,Object> patch=null;
            if(raw.hasNonNull("payload")) {
                if(!raw.get("payload").isObject() || raw.get("payload").size()>50) throw denied();
                patch=store.object(new String(json.bytes(raw.get("payload")),java.nio.charset.StandardCharsets.UTF_8));
            }
            command=new ApprovalWorkflowQuorumInformationRuntime.ReplyCommand(request,version,generation,key,text(raw,"message",2000),patch,sha(bytes));
        }
        String digest=store.hash(ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(Map.of("operation",lookup.operation(),
                "tenantId",actor.tenantId(),"actorUserId",actor.userId(),"actorPersonId",actor.personPublicId().toString(),"command",store.object(store.json(command))))));
        if(!digest.equals(hash(admission,"commandSha256"))) throw denied();return digest;
    }
    private ApprovalInformationReceiptCommand() { }
}
