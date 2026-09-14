package com.dwp.services.approval.workflowauthority;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.*;

import com.dwp.services.approval.domain.ApprovalWorkflowRuntimeSource;
import com.dwp.services.approval.domain.ApprovalWorkflowRuntimeTarget;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Typed locked operations only. There is deliberately no unbound voter(snapshot,actor,principal) fallback. */
public final class WorkflowRuntimeCurrentSource implements com.dwp.services.approval.domain.ApprovalWorkflowBoundAuthority {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectProvider<HttpServletRequest> requests;
    private final WorkflowRuntimeActionContext contexts;
    private final WorkflowRuntimeProofIssuer issuer;
    private final WorkflowRuntimeAuthorityClient client;
    public WorkflowRuntimeCurrentSource(NamedParameterJdbcTemplate jdbc,ObjectProvider<HttpServletRequest> requests,
            WorkflowRuntimeActionContext contexts,WorkflowRuntimeProofIssuer issuer,WorkflowRuntimeAuthorityClient client) {
        this.jdbc=jdbc;this.requests=requests;this.contexts=contexts;this.issuer=issuer;this.client=client;
    }
    public CandidatePool sealedCandidates(Snapshot snapshot) {
        var current=pool(candidates(snapshot),snapshot);
        var frozen=current.subjects().stream().filter(subject->snapshot.candidates().contains(new Candidate(subject.userId(),subject.personPublicId()))).toList();
        return new CandidatePool(current.tenantId(),current.workflowVersionId(),current.candidateRole(),frozen,current.authorityRevision(),
                true,false,current.evaluatedAt(),current.expiresAt());
    }
    @Override public CandidatePool candidates(Pins pins,UUID request,com.dwp.services.approval.domain.ApprovalWorkflowQuorumDefinition.Stage stage,Instant now) {
        throw unavailable();
    }
    @Override public CurrentAuthority voter(Snapshot snapshot,long actor,long principal,Instant now) {throw unavailable();}
    @Override public CurrentAuthority voter(Snapshot snapshot,ApprovalWorkflowRuntimeTarget.Use use,UUID task,UUID evidence,Instant now) {
        return voter(snapshot,use,task,evidence);
    }
    @Override public CandidatePool activation(long tenant,UUID requestId,UUID step,long generation,Instant now) {
        var request=requireRequest();var before=activation(tenant,requestId,step,generation,request);
        var proof=client.evaluate(issuer.issue(Operation.CANDIDATES,ApprovalRequestContext.require().userId(),before.bindings(),before.expiresAt()));
        before.requireSame(activation(tenant,requestId,step,generation,request));
        var owner=before.bindings().get("owner");var stage=before.bindings().get("stage");String role=text(stage,"candidateRole",100);
        long roleId=integer(proof.result().get("role"),"roleId",1);var subjects=new java.util.ArrayList<Subject>();
        for(var member:proof.result().get("members")) subjects.add(subject(member,role,roleId));
        if(before.snapshot()!=null) {
            var eligible=subjects.stream().filter(subject->before.snapshot().candidates().contains(new Candidate(subject.userId(),subject.personPublicId()))).toList();
            if(eligible.size()!=before.snapshot().candidates().size()) throw denied();subjects=new java.util.ArrayList<>(eligible);
        }
        return new CandidatePool(tenant,uuid(owner,"workflowVersionId"),role,subjects,text(proof.authority(),"sourceRevision",68),true,false,
                Instant.ofEpochSecond(integer(proof.authority(),"evaluatedAt",1)),proof.expiresAt());
    }
    public CurrentAuthority voter(Snapshot snapshot,ApprovalWorkflowRuntimeTarget.Use use,UUID taskId,UUID evidenceId) {
        var role=candidates(snapshot);
        var request=requireRequest();var before=seal(snapshot,request);
        var target=ApprovalWorkflowRuntimeTarget.seal(jdbc,snapshot,use,taskId,evidenceId).withVerifiedRole(role);
        var bindings=(com.fasterxml.jackson.databind.node.ObjectNode)before.bindings();bindings.set("target",target.json());
        Instant expires=before.expiresAt().isBefore(role.expiresAt())?before.expiresAt():role.expiresAt();
        var proof=client.evaluate(issuer.issue(Operation.VOTER,ApprovalRequestContext.require().userId(),bindings,expires));
        before.requireSame(seal(snapshot,request));
        var currentTarget=ApprovalWorkflowRuntimeTarget.seal(jdbc,snapshot,use,taskId,evidenceId).withVerifiedRole(role);
        var json=new WorkflowRuntimeJson();
        if (!java.util.Arrays.equals(json.bytes(target.json()),json.bytes(currentTarget.json())) || !role.expiresAt().isAfter(Instant.now())) throw denied();
        var result=proof.result();long roleId=integer(role.result().get("role"),"roleId",1);
        Subject actor=subject(result.get("actor"),snapshot.candidateRole(),roleId);
        Subject principal=subject(result.get("principal"),snapshot.candidateRole(),roleId);
        Delegation delegation=null;var grant=result.get("delegation");
        if(!grant.isNull()) {
            if(integer(grant,"authorityRoleId",1)!=roleId) throw denied();
            delegation=new Delegation(uuid(grant,"id"),snapshot.pins().tenantId(),uuid(grant,"workflowVersionId"),
                    integer(grant,"delegatorUserId",1),integer(grant,"delegateUserId",1),snapshot.candidateRole(),true,
                    Instant.ofEpochSecond(integer(grant,"startsAt",1)),Instant.ofEpochSecond(integer(grant,"endsAt",1)));
        }
        return new CurrentAuthority(AccessMode.valueOf(text(bindings.get("owner"),"accessMode",30)),text(proof.authority(),"sourceRevision",68),
                Instant.ofEpochSecond(integer(proof.authority(),"evaluatedAt",1)),proof.expiresAt(),actor,principal,delegation);
    }
    private WorkflowRuntimeAttestationVerifier.Verified candidates(Snapshot snapshot) {
        var request=requireRequest();var before=seal(snapshot,request);
        var proof=client.evaluate(issuer.issue(Operation.CANDIDATES,ApprovalRequestContext.require().userId(),before.bindings(),before.expiresAt()));
        before.requireSame(seal(snapshot,request));
        var pool=pool(proof,snapshot);
        var eligible=new com.dwp.services.approval.domain.ApprovalWorkflowQuorumEvaluator().eligibleCandidates(snapshot.pins(),snapshot.candidateRole(),pool,
                snapshot.requesterUserId(),snapshot.requesterPersonPublicId(),Instant.now());
        // Additional current members may exist, but they cannot replace any frozen identity or alter this stage's denominator.
        if(!eligible.containsAll(snapshot.candidates())) throw denied();
        return proof;
    }
    private CandidatePool pool(WorkflowRuntimeAttestationVerifier.Verified proof,Snapshot snapshot) {
        long roleId=integer(proof.result().get("role"),"roleId",1);var subjects=new java.util.ArrayList<Subject>();
        for(var member:proof.result().get("members")) subjects.add(subject(member,snapshot.candidateRole(),roleId));
        return new CandidatePool(snapshot.pins().tenantId(),snapshot.pins().workflowVersionId(),snapshot.candidateRole(),subjects,
                text(proof.authority(),"sourceRevision",68),true,false,Instant.ofEpochSecond(integer(proof.authority(),"evaluatedAt",1)),proof.expiresAt());
    }
    private Subject subject(JsonNode value,String role,long roleId) {
        var ids=value.get("roleIds");boolean matched=ids.size()==1 && ids.get(0).longValue()==roleId;
        return new Subject(integer(value,"tenantId",1),integer(value,"userId",1),uuid(value,"personPublicId"),IdentityPlane.TENANT,
                true,matched?Set.of(role):Set.of(),value.get("canApprove").booleanValue());
    }
    private ApprovalWorkflowRuntimeSource seal(Snapshot snapshot,HttpServletRequest request) {
        var actor=ApprovalRequestContext.require();String key=request.getHeader("Idempotency-Key");
        var context=com.dwp.services.approval.security.ApprovalDecisionRevisionContext.current().orElseThrow(WorkflowRuntimeProtocol::unavailable);
        var current=contexts.require(request,actor,context.routeContractKey(),request.getMethod(),request.getRequestURI(),key);
        Object stored=request.getAttribute(WorkflowRuntimeInformationAdmission.ATTRIBUTE);
        if(stored!=null && !(stored instanceof WorkflowRuntimeAttestationVerifier.Verified)) throw denied();
        return ApprovalWorkflowRuntimeSource.seal(jdbc,snapshot,current,request.getMethod(),request.getRequestURI(),key,
                (WorkflowRuntimeAttestationVerifier.Verified)stored);
    }
    private ApprovalWorkflowRuntimeSource activation(long tenant,UUID requestId,UUID step,long generation,HttpServletRequest request) {
        var actor=ApprovalRequestContext.require();String key=request.getHeader("Idempotency-Key");
        var context=com.dwp.services.approval.security.ApprovalDecisionRevisionContext.current().orElseThrow(WorkflowRuntimeProtocol::unavailable);
        var current=contexts.require(request,actor,context.routeContractKey(),request.getMethod(),request.getRequestURI(),key);
        Object stored=request.getAttribute(WorkflowRuntimeInformationAdmission.ATTRIBUTE);
        if(stored!=null && !(stored instanceof WorkflowRuntimeAttestationVerifier.Verified)) throw denied();
        return ApprovalWorkflowRuntimeSource.activation(jdbc,tenant,requestId,step,generation,current,request.getMethod(),request.getRequestURI(),key,
                (WorkflowRuntimeAttestationVerifier.Verified)stored);
    }
    private HttpServletRequest requireRequest() {
        var request=requests.getIfAvailable();if(request==null) throw unavailable();return request;
    }
}
