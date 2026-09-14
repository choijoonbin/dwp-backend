package com.dwp.services.approval.attachment;

import com.dwp.services.approval.document.*;
import com.dwp.services.approval.security.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.dwp.services.approval.attachment.ApprovalAttachmentDtos.*;

@Service
public class ApprovalAttachmentManagementService {
    public static final String PUBLISH="route.approvals.admin.attachment-policy-publish.action";
    private final ApprovalDocumentAuthority authority;
    private final ApprovalAttachmentPolicyRepository policies;
    private final ApprovalAttachmentCommands commands;
    private final ApprovalDocumentPublishGuard guard;
    private final ApprovalAttachmentProviderGate provider;
    private final ApprovalAttachmentAudit audit;
    public ApprovalAttachmentManagementService(ApprovalDocumentAuthority authority,ApprovalAttachmentPolicyRepository policies,ApprovalAttachmentCommands commands,
            ApprovalDocumentPublishGuard guard,ApprovalAttachmentProviderGate provider,ApprovalAttachmentAudit audit) {this.authority=authority;this.policies=policies;this.commands=commands;this.guard=guard;this.provider=provider;this.audit=audit;}
    @Transactional public Policy policy() {
        String scope=authority.management("ADMIN.APPROVAL_POLICY:VIEW");var actor=ApprovalRequestContext.require();
        var head=policies.current(actor,scope,null,false);authority.management("ADMIN.APPROVAL_POLICY:VIEW");return projection(actor,head);
    }
    @Transactional public Policy initialize(InitializePolicy input) {
        String permission="ADMIN.APPROVAL_POLICY:UPDATE",scope=authority.management(permission);
        var actor=ApprovalRequestContext.require();String route="/v1/admin/attachments/policies";
        if(input==null || !Boolean.TRUE.equals(input.expectedAbsent())) throw ApprovalDocumentCanonical.conflict();
        var binding=Map.<String,Object>of("resourceSetKey",scope,"input",input);
        var prior=commands.replay(actor,route,input.idempotencyKey(),binding);
        ApprovalAttachmentPolicyRepository.Head head;
        if(prior==null) head=policies.initializeAbsent(actor,scope);
        else {
            UUID target;
            try {target=UUID.fromString((String)prior.get("policyId"));}
            catch(RuntimeException malformed){throw ApprovalDocumentCanonical.unavailable("Attachment policy receipt integrity failed.");}
            if(!scope.equals(prior.get("resourceSetKey"))) throw ApprovalDocumentCanonical.forbidden();
            head=policies.current(actor,scope,target,false);
        }
        Policy response=projection(actor,head);
        if(!scope.equals(authority.management(permission))) throw ApprovalDocumentCanonical.forbidden();
        if(prior==null) {
            var metadata=Map.<String,Object>of("policyId",head.policyId(),"resourceSetKey",scope,"version",head.version(),"rulesSha256",head.publishedRulesSha256());
            commands.complete(actor,route,input.idempotencyKey(),binding,metadata,head.rules().retentionDays());
            audit.recordPolicy(actor.tenantId(),actor.userId(),head.policyId(),"APPROVAL_ATTACHMENT_POLICY_INITIALIZED",input.idempotencyKey(),metadata);
        }
        return response;
    }
    @Transactional public Policy save(UUID id,SavePolicy input) {
        String scope=authority.management("ADMIN.APPROVAL_POLICY:UPDATE");var actor=ApprovalRequestContext.require();
        var head=policies.current(actor,scope,id,true);String route="/v1/admin/attachments/policies/"+id+"/draft";
        var prior=commands.replay(actor,route,input.idempotencyKey(),input);
        if(prior!=null){authority.management("ADMIN.APPROVAL_POLICY:UPDATE");return projection(actor,head);}
        if(input.expectedVersion()==null || input.expectedVersion()!=head.version()) throw ApprovalDocumentCanonical.conflict();
        policies.draft(actor,head,input.rules());authority.management("ADMIN.APPROVAL_POLICY:UPDATE");
        finish(actor,id,route,input.idempotencyKey(),input,head.version()+1,input.rules().retentionDays(),"APPROVAL_ATTACHMENT_POLICY_DRAFT_SAVED");
        return projection(actor,policies.current(actor,scope,id,true));
    }
    @Transactional public Policy publish(UUID id,PublishPolicy input,ApprovalStepUpHeaders headers) {
        String scope=authority.management("ADMIN.APPROVAL_POLICY:PUBLISH");var actor=ApprovalRequestContext.require();
        var head=policies.current(actor,scope,id,true);String route="/v1/admin/attachments/policies/"+id+"/publish";
        var prior=commands.replay(actor,route,input.idempotencyKey(),input);
        if(input.expectedVersion()==null) throw ApprovalDocumentCanonical.conflict();
        var challenge=guard.verify(actor,PUBLISH,"ATTACHMENT_POLICY",id,input.expectedVersion(),"/api/approvals"+route,input.idempotencyKey(),input,headers);
        if(prior!=null){authority.management("ADMIN.APPROVAL_POLICY:PUBLISH");return projection(actor,head);}
        if(input.expectedVersion()!=head.version() || head.pending()==null) throw ApprovalDocumentCanonical.conflict();
        if(head.pending().allowUpload()) provider.requireIngestion();
        if(head.pending().allowDownload()) provider.requireDownload();
        policies.publish(actor,head,input.reviewComment());authority.management("ADMIN.APPROVAL_POLICY:PUBLISH");guard.consume(challenge);
        finish(actor,id,route,input.idempotencyKey(),input,head.version()+1,head.pending().retentionDays(),"APPROVAL_ATTACHMENT_POLICY_PUBLISHED");
        return projection(actor,policies.current(actor,scope,id,true));
    }
    private Policy projection(ApprovalRequestContext.Actor actor,ApprovalAttachmentPolicyRepository.Head head){
        return head.projection(actor.userId(),provider.readiness(),provider.downloadReadiness());
    }
    private void finish(ApprovalRequestContext.Actor actor,UUID id,String route,String key,Object input,long version,int days,String action){
        var metadata=Map.<String,Object>of("policyId",id,"version",version);commands.complete(actor,route,key,input,metadata,days);audit.record(actor.tenantId(),actor.userId(),id,action,key,metadata);
    }
}
