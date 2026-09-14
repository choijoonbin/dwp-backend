package com.dwp.services.approval.domain;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.denied;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.unavailable;
import static com.dwp.services.approval.security.ApprovalWorkflowQuorumCommandProof.Purpose.*;

import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import com.dwp.services.approval.security.ApprovalWorkflowQuorumCommandMetadata;
import com.dwp.services.approval.security.ApprovalWorkflowQuorumCommandProof.Purpose;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeAttestationVerifier;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeInformationAdmission;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** The completed-business callback carries only the genuine original admission, never a replay credential. */
@Service
public class ApprovalWorkflowInformationCompletionAdmissions {
    private final ObjectProvider<HttpServletRequest> requests;
    private final ApprovalWorkflowQuorumCommandMetadata metadata;
    private final ApprovalWorkflowInformationAdmissionLedger ledger;
    public ApprovalWorkflowInformationCompletionAdmissions(ObjectProvider<HttpServletRequest> requests,
            ApprovalWorkflowQuorumCommandMetadata metadata,NamedParameterJdbcTemplate jdbc,ObjectMapper mapper) {
        this.requests=requests;this.metadata=metadata;ledger=new ApprovalWorkflowInformationAdmissionLedger(jdbc,mapper);
    }
    public Consumer<ApprovalWorkflowQuorumInformationRuntime.Receipt> request(Actor actor,ApprovalWorkflowQuorumInformationRuntime.RequestCommand command) {
        if(command==null) throw denied();
        var proof=current(actor,TASK_INFORMATION,command.taskId(),command.idempotencyKey(),command.rawBodySha256());
        return receipt->{same(proof,current(actor,TASK_INFORMATION,command.taskId(),command.idempotencyKey(),command.rawBodySha256()));
            stamp(proof,()->ledger.append(ledger.request(actor,command,receipt,proof)));};
    }
    public Consumer<ApprovalWorkflowQuorumInformationRuntime.Receipt> reply(Actor actor,ApprovalWorkflowQuorumInformationRuntime.ReplyCommand command) {
        if(command==null) throw denied();
        var proof=current(actor,REQUEST_REPLY,command.requestId(),command.idempotencyKey(),command.rawBodySha256());
        return receipt->{same(proof,current(actor,REQUEST_REPLY,command.requestId(),command.idempotencyKey(),command.rawBodySha256()));
            stamp(proof,()->ledger.append(ledger.reply(actor,command,receipt,proof)));};
    }
    private WorkflowRuntimeAttestationVerifier.Verified current(Actor actor,Purpose purpose,UUID target,String key,String raw) {
        if(actor==null || !actor.equals(ApprovalRequestContext.require()) || target==null) throw denied();
        var identity=metadata.current(actor,purpose,target);
        if(!identity.idempotencyKey().equals(key) || !identity.rawBodySha256().equals(raw)) throw denied();
        var request=requests.getIfAvailable();if(request==null) throw unavailable();
        var stored=request.getAttribute(WorkflowRuntimeInformationAdmission.ATTRIBUTE);
        if(stored==null) throw unavailable();
        if(!(stored instanceof WorkflowRuntimeAttestationVerifier.Verified proof) || proof.operation()!=WorkflowRuntimeProtocol.Operation.INFORMATION_ADMISSION) throw denied();
        var signed=proof.bindings().get("command");var context=identity.context();
        if(!proof.expiresAt().isAfter(Instant.now())) throw unavailable();
        if(signed==null || integer(signed,"tenantId",1)!=actor.tenantId() || integer(signed,"actorId",1)!=actor.userId()
                || !uuid(signed,"personPublicId").equals(actor.personPublicId()) || !text(signed,"commandPurpose",40).equals(purpose.name())
                || !uuid(signed,"targetId").equals(target) || !text(signed,"idempotencyKey",120).equals(key) || !hash(signed,"rawBodySha256").equals(raw)
                || !text(signed,"contextKey",500).equals(context.contextKey()) || !text(signed,"contextScopeKey",500).equals(context.contextScopeKey())
                || !text(signed,"decisionRevision",68).equals(context.revision()) || !text(signed,"routeContractKey",100).equals(context.routeContractKey())
                || !text(signed,"method",4).equals(identity.method()) || !text(signed,"path",250).equals(identity.path())
                || !text(signed,"accessMode",10).equals(identity.accessMode()) || !text(signed,"rolloutState",3).equals(context.rolloutState())
                || integer(signed,"authorityValidUntil",1)!=context.validUntil().toInstant().getEpochSecond()
                || !text(proof.authority(),"sourceRevision",68).equals(identity.revision())
                || integer(proof.authority(),"evaluatedAt",1)!=identity.evaluatedAt().getEpochSecond()
                || !proof.expiresAt().equals(identity.expiresAt())) throw denied();
        return proof;
    }
    private static void same(WorkflowRuntimeAttestationVerifier.Verified expected,WorkflowRuntimeAttestationVerifier.Verified actual) {
        if(expected!=actual) throw denied();
    }
    private static void stamp(WorkflowRuntimeAttestationVerifier.Verified proof,Runnable append) {
        try {append.run();}
        catch(com.dwp.core.exception.BaseException error) {
            if(error.getErrorCode()==com.dwp.core.common.ErrorCode.FORBIDDEN && !proof.expiresAt().isAfter(Instant.now())) throw unavailable();
            throw error;
        }
    }
}
