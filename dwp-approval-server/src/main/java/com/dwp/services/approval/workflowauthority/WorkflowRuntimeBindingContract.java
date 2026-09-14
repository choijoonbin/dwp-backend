package com.dwp.services.approval.workflowauthority;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.*;

import com.dwp.services.approval.domain.ApprovalWorkflowQuorumDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Approval validates the closed signed table as well as the attestation; Auth independently reevaluates authority. */
final class WorkflowRuntimeBindingContract {
    private static final Set<String> OWNER=Set.of("tenantId","actorId","personPublicId","requestId","requestVersion","workflowVersionId","workflowVersion",
            "workflowDefinitionSha256","formVersionId","formSchemaSha256","payloadRevision","payloadSha256","policyVersion","policySha256","contextKey","contextScopeKey",
            "decisionRevision","accessMode","routeContractKey","managementResourceSetKey","roleCodes","publishedDefinition","method","path","idempotencyKey");
    private static final Set<String> STAGE=Set.of("poolMode","stepKey","generation","requesterUserId","requesterPersonPublicId","candidateRole","sourceStageRevision",
            "snapshotSha256","candidateSetSha256","candidateCount","requiredVotes","rejectCommentMinLength","admissionAttestationSha256","admissionAttestation","commandRawBodySha256");
    private static final Set<String> TARGET=Set.of("use","stepKey","taskId","evidenceId","evidenceSha256","sourceGeneration","actorId","actorPersonPublicId",
            "principalId","principalPersonPublicId","delegation");
    private static final Set<String> DELEGATION=Set.of("id","delegatorUserId","delegateUserId","delegatePersonPublicId","workflowVersionId","authorityRoleId","startsAt","endsAt");
    private WorkflowRuntimeBindingContract() { }
    static void validate(Operation operation,JsonNode bindings) {
        if(operation==Operation.INFORMATION_ADMISSION) {exact(bindings,Set.of("command"));exact(bindings.get("command"),COMMAND_FIELDS);return;}
        exact(bindings,operation==Operation.CANDIDATES?Set.of("owner","stage"):Set.of("owner","stage","target"));
        var owner=bindings.get("owner");var stage=bindings.get("stage");exact(owner,OWNER);exact(stage,STAGE);
        integer(owner,"tenantId",1);integer(owner,"actorId",1);uuid(owner,"personPublicId");uuid(owner,"requestId");uuid(owner,"workflowVersionId");uuid(owner,"formVersionId");
        integer(owner,"requestVersion",0);for(String key:Set.of("workflowVersion","payloadRevision","policyVersion")) integer(owner,key,1);
        for(String key:Set.of("workflowDefinitionSha256","formSchemaSha256","payloadSha256","policySha256")) hash(owner,key);
        for(String key:Set.of("contextKey","contextScopeKey","managementResourceSetKey")) text(owner,key,200);
        if(!text(owner,"decisionRevision",68).matches("psr-[a-f0-9]{64}") || !Set.of("NORMAL","ELEVATED").contains(text(owner,"accessMode",30)) || !"POST".equals(text(owner,"method",4))) throw denied();
        String route=text(owner,"routeContractKey",160),path=text(owner,"path",200);String request=uuid(owner,"requestId").toString();
        boolean canonical=switch(route) {
            case "route.approvals.work.request-submit.action" -> path.equals("/v1/requests/"+request+"/submit");
            case "route.approvals.work.request-information-response.action" -> path.equals("/v1/requests/"+request+"/information-response");
            case "route.approvals.work.task-decision.action" -> taskPath(path);
            default -> false;
        };
        if(!canonical || !text(owner,"idempotencyKey",120).matches("[A-Za-z0-9._:-]{1,120}")) throw denied();
        var definition=ApprovalWorkflowQuorumDefinition.compile(text(owner,"publishedDefinition",131072));
        if(!definition.sha256().equals(hash(owner,"workflowDefinitionSha256"))) throw denied();
        var declared=definition.stages().stream().filter(item->item.key().equals(text(stage,"stepKey",50))).findFirst().orElseThrow(WorkflowRuntimeProtocol::denied);
        if(!declared.candidateRole().equals(text(stage,"candidateRole",100))) throw denied();
        var codes=owner.get("roleCodes");var expected=definition.stages().stream().map(ApprovalWorkflowQuorumDefinition.Stage::candidateRole).distinct().sorted().toList();
        if(!codes.isArray() || codes.size()!=expected.size()) throw denied();
        for(int i=0;i<codes.size();i++) if(!codes.get(i).isTextual() || !expected.get(i).equals(codes.get(i).textValue())) throw denied();
        long generation=integer(stage,"generation",1);integer(stage,"requesterUserId",1);uuid(stage,"requesterPersonPublicId");integer(stage,"sourceStageRevision",0);
        if(integer(stage,"rejectCommentMinLength",4)>1000) throw denied();String mode=text(stage,"poolMode",20);
        if(!Set.of("INITIAL","REBUILD","RETAINED","SEALED").contains(mode) || operation==Operation.VOTER && !mode.equals("SEALED")
                || mode.equals("INITIAL") && generation!=1 || Set.of("REBUILD","RETAINED").contains(mode) && generation<=1) throw denied();
        if(Set.of("INITIAL","REBUILD").contains(mode)) {
            for(String key:Set.of("snapshotSha256","candidateSetSha256","candidateCount","requiredVotes")) if(!stage.get(key).isNull()) throw denied();
        } else {
            hash(stage,"snapshotSha256");hash(stage,"candidateSetSha256");long count=integer(stage,"candidateCount",1);
            if(count>1000 || integer(stage,"requiredVotes",1)!=declared.quorum().threshold((int)count)) throw denied();
        }
        if(stage.get("admissionAttestation").isNull()) {
            for(String key:Set.of("admissionAttestationSha256","commandRawBodySha256")) if(!stage.get(key).isNull()) throw denied();
            if(route.equals("route.approvals.work.request-information-response.action")) throw denied();
        } else {
            if(!sha(text(stage,"admissionAttestation",ATTESTATION_MAX)).equals(hash(stage,"admissionAttestationSha256"))) throw denied();hash(stage,"commandRawBodySha256");
        }
        if(operation==Operation.VOTER) target(bindings);
    }
    private static void target(JsonNode bindings) {
        var owner=bindings.get("owner");var stage=bindings.get("stage");var target=bindings.get("target");exact(target,TARGET);
        if(!text(target,"stepKey",50).equals(text(stage,"stepKey",50)) || integer(target,"sourceGeneration",1)!=integer(stage,"generation",1)) throw denied();
        var task=uuid(target,"taskId");uuid(target,"actorPersonPublicId");uuid(target,"principalPersonPublicId");
        long actor=integer(target,"actorId",1),principal=integer(target,"principalId",1);String use=text(target,"use",30);
        if(!Set.of("CAST","SEAT_RECHECK","VOTE_RECHECK","INFORMATION_RECHECK").contains(use)) throw denied();
        if(Set.of("CAST","SEAT_RECHECK").contains(use)) {if(!target.get("evidenceId").isNull() || !target.get("evidenceSha256").isNull()) throw denied();}
        else {uuid(target,"evidenceId");hash(target,"evidenceSha256");}
        if(use.equals("CAST") && (actor!=integer(owner,"actorId",1) || text(owner,"routeContractKey",160).equals("route.approvals.work.task-decision.action")
                && !text(owner,"path",200).equals("/v1/tasks/"+task+"/decisions")) || use.equals("SEAT_RECHECK") && actor!=principal) throw denied();
        if(use.equals("INFORMATION_RECHECK") && stage.get("admissionAttestation").isNull()) throw denied();
        var grant=target.get("delegation");
        if(actor==principal) {if(!grant.isNull() || !uuid(target,"actorPersonPublicId").equals(uuid(target,"principalPersonPublicId"))) throw denied();}
        else {
            exact(grant,DELEGATION);uuid(grant,"id");integer(grant,"authorityRoleId",1);
            if(integer(grant,"delegatorUserId",1)!=principal || integer(grant,"delegateUserId",1)!=actor
                    || !uuid(grant,"delegatePersonPublicId").equals(uuid(target,"actorPersonPublicId")) || !uuid(grant,"workflowVersionId").equals(uuid(owner,"workflowVersionId"))
                    || integer(grant,"endsAt",1)<=integer(grant,"startsAt",1)) throw denied();
        }
    }
    private static boolean taskPath(String path) {
        if(!path.startsWith("/v1/tasks/") || !path.endsWith("/decisions")) return false;
        try {String id=path.substring(10,path.length()-10);return java.util.UUID.fromString(id).toString().equals(id);} catch(IllegalArgumentException error) {return false;}
    }
}
