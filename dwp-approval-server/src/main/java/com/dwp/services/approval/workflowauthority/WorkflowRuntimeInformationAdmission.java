package com.dwp.services.approval.workflowauthority;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.*;

import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.ApprovalWorkflowQuorumCommandProof;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;

/** Admission performs no Approval SQL. The verified JWT remains request-local causal evidence for locked phase two. */
public final class WorkflowRuntimeInformationAdmission implements ApprovalWorkflowQuorumCommandProof {
    public static final String ATTRIBUTE=WorkflowRuntimeInformationAdmission.class.getName()+".verified";
    private final ObjectProvider<HttpServletRequest> requests;
    private final WorkflowRuntimeActionContext contexts;
    private final WorkflowRuntimeJson json;
    private final WorkflowRuntimeProofIssuer issuer;
    private final WorkflowRuntimeAuthorityClient client;
    public WorkflowRuntimeInformationAdmission(ObjectProvider<HttpServletRequest> requests,WorkflowRuntimeActionContext contexts,
            WorkflowRuntimeJson json,WorkflowRuntimeProofIssuer issuer,WorkflowRuntimeAuthorityClient client) {
        this.requests=requests;this.contexts=contexts;this.json=json;this.issuer=issuer;this.client=client;
    }
    @Override public Verified verify(ApprovalRequestContext.Actor actor,Purpose purpose,UUID target,String method,String path,String originalKey,byte[] originalBody) {
        if (purpose==null || target==null || !"POST".equals(method)) throw denied();
        String canonical=purpose==Purpose.TASK_INFORMATION ? "/v1/tasks/"+target+"/decisions" : "/v1/requests/"+target+"/information-response";
        String route=purpose==Purpose.TASK_INFORMATION ? "route.approvals.work.task-decision.action" : "route.approvals.work.request-information-response.action";
        if (!canonical.equals(path)) throw denied();
        var request=requests.getIfAvailable();var before=contexts.require(request,actor,route,method,path,originalKey);
        var body=json.parse(originalBody,OWNER_MAX);long expected=integer(body,"expectedVersion",0);
        Long generation=purpose==Purpose.REQUEST_REPLY ? integer(body,"sourceGeneration",1) : null;
        if (purpose==Purpose.TASK_INFORMATION && !"REQUEST_INFO".equals(text(body,"decision",30))) throw denied();
        var command=json.object();command.put("tenantId",actor.tenantId());command.put("actorId",actor.userId());command.put("personPublicId",actor.personPublicId().toString());
        command.put("commandPurpose",purpose.name());command.put("targetId",target.toString());command.put("routeContractKey",route);command.put("method",method);command.put("path",path);
        command.put("idempotencyKey",originalKey);command.put("rawBodySha256",sha(originalBody));command.put("contextKey",before.context().contextKey());command.put("contextScopeKey",before.context().contextScopeKey());
        command.put("decisionRevision",before.context().revision());command.put("accessMode",before.mode());command.put("rolloutState",before.context().rolloutState());
        command.put("authorityValidUntil",before.context().validUntil().toInstant().getEpochSecond());command.put("expectedVersion",expected);
        if (generation==null) command.putNull("sourceGeneration");else command.put("sourceGeneration",generation);
        var bindings=json.object();bindings.set("command",command);
        var result=client.evaluate(issuer.issue(Operation.INFORMATION_ADMISSION,actor.userId(),bindings,before.context().validUntil().toInstant()));
        if (!before.equals(contexts.require(request,actor,route,method,path,originalKey))) throw unavailable();
        request.setAttribute(ATTRIBUTE,result);
        return new Verified(actor.tenantId(),actor.userId(),actor.personPublicId(),purpose,target,method,path,originalKey,sha(originalBody),
                text(result.authority(),"sourceRevision",68),Instant.ofEpochSecond(integer(result.authority(),"evaluatedAt",1)),result.expiresAt(),before.context(),before.mode());
    }
}
