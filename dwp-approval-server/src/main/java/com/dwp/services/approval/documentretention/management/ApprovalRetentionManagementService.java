package com.dwp.services.approval.documentretention.management;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.dwp.services.approval.security.*;
import com.dwp.services.approval.documentretention.management.receipt.ApprovalRetentionCommandWitnessRepository;
import static com.dwp.services.approval.documentretention.management.receipt.ApprovalRetentionCommandProfile.Operation;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.dwp.services.approval.documentretention.management.ApprovalRetentionDtos.*;
import com.dwp.services.approval.documentretention.management.ApprovalRetentionDtos.Record;

@Service
public class ApprovalRetentionManagementService {
    public static final String POLICY_PUBLISH="route.approvals.admin.retention-policy-publish.action";
    public static final String RECORD_CLAIM="route.approvals.admin.retention-record-claim.action";
    private final ApprovalRetentionAuthority authority;
    private final ApprovalRetentionManagementRepository repository;
    private final ApprovalRetentionPolicyValidator validator;
    private final ApprovalRetentionEligibility eligibility;
    private final ApprovalRetentionHighGuard high;
    private final ApprovalRetentionForeignJournal foreign;
    private final ApprovalRetentionAudit audit;
    private final ApprovalRetentionCommandWitnessRepository witnesses;
    public ApprovalRetentionManagementService(ApprovalRetentionAuthority authority,ApprovalRetentionManagementRepository repository,
            ApprovalRetentionPolicyValidator validator,ApprovalRetentionEligibility eligibility,ApprovalRetentionHighGuard high,
            ApprovalRetentionForeignJournal foreign,ApprovalRetentionAudit audit,ApprovalRetentionCommandWitnessRepository witnesses) {
        this.authority=authority;this.repository=repository;this.validator=validator;this.eligibility=eligibility;
        this.high=high;this.foreign=foreign;this.audit=audit;this.witnesses=witnesses;
    }
    @Transactional public Policy policy() {
        var actor=authority.require("ADMIN.APPROVAL_POLICY:VIEW");var result=repository.policy(actor,authority.scope(),null,false);
        authority.require("ADMIN.APPROVAL_POLICY:VIEW");return result;
    }
    @Transactional public Policy initialize(InitializePolicy input) {
        var actor=authority.require("ADMIN.APPROVAL_POLICY:UPDATE");String scope=authority.scope(),path="/v1/admin/retention/policies";
        repository.tenant(actor);
        var prior=repository.receipt(actor,scope,path,input.idempotencyKey(),input);
        if(prior!=null) {authority.require("ADMIN.APPROVAL_POLICY:UPDATE");return repository.policy(actor,scope,prior,false);}
        if(!Boolean.TRUE.equals(input.expectedAbsent())) throw ApprovalRetentionErrors.conflict();
        var captured=witnesses.prepare(actor,scope,Operation.INITIALIZE_POLICY,null,input,null,null,null,validator.defaults().receiptRetentionDays());
        UUID id=repository.initialize(actor,scope,validator.defaults());authority.require("ADMIN.APPROVAL_POLICY:UPDATE");
        repository.complete(actor,scope,path,input.idempotencyKey(),input,id,0);witnesses.commit(captured,id,0);
        audit.record(actor,id,"APPROVAL_RETENTION_POLICY_INITIALIZED",input.idempotencyKey(),0);
        return repository.policy(actor,scope,id,false);
    }
    @Transactional public Policy save(UUID policyId,SavePolicy input) {
        var actor=authority.require("ADMIN.APPROVAL_POLICY:UPDATE");String scope=authority.scope(),path="/v1/admin/retention/policies/"+policyId+"/draft";
        var policy=repository.policy(actor,scope,policyId,true);
        var prior=repository.receipt(actor,scope,path,input.idempotencyKey(),input);
        if(prior!=null) {authority.require("ADMIN.APPROVAL_POLICY:UPDATE");return policy;}
        if(input.expectedVersion()==null || input.expectedVersion()!=policy.version()) throw ApprovalRetentionErrors.conflict();
        validator.validate(input.rules());
        var captured=witnesses.prepare(actor,scope,Operation.SAVE_POLICY,policyId,input,null,null,null,policy.published().receiptRetentionDays());
        repository.save(actor,policy,input.rules());authority.require("ADMIN.APPROVAL_POLICY:UPDATE");
        repository.complete(actor,scope,path,input.idempotencyKey(),input,policyId,policy.version()+1);
        witnesses.commit(captured,policyId,policy.version()+1);
        audit.record(actor,policyId,"APPROVAL_RETENTION_POLICY_DRAFT_SAVED",input.idempotencyKey(),policy.version()+1);
        return repository.policy(actor,scope,policyId,false);
    }
    @Transactional public Policy publish(UUID policyId,PublishPolicy input,ApprovalStepUpHeaders headers) {
        var actor=authority.require("ADMIN.APPROVAL_POLICY:PUBLISH");String scope=authority.scope(),path="/v1/admin/retention/policies/"+policyId+"/publish";
        witnesses.validateText(input);
        var policy=repository.policy(actor,scope,policyId,true);
        var prior=repository.receipt(actor,scope,path,input.idempotencyKey(),input);
        var challenge=high.verify(actor,POLICY_PUBLISH,"approvals.policy.publish","RETENTION_POLICY",policyId,input.expectedVersion(),path,input.idempotencyKey(),input,headers);
        if(prior!=null) {authority.require("ADMIN.APPROVAL_POLICY:PUBLISH");return policy;}
        if(input.expectedVersion()!=policy.version() || policy.pending()==null) throw ApprovalRetentionErrors.conflict();
        validator.validate(policy.pending());
        var captured=witnesses.prepare(actor,scope,Operation.PUBLISH_POLICY,policyId,input,policy.pendingMakerUserId(),policy.pendingRevision(),challenge,policy.published().receiptRetentionDays());
        repository.publish(actor,policy,input.reviewComment());authority.require("ADMIN.APPROVAL_POLICY:PUBLISH");high.consume(challenge);
        repository.complete(actor,scope,path,input.idempotencyKey(),input,policyId,policy.version()+1);
        witnesses.commit(captured,policyId,policy.version()+1);
        audit.record(actor,policyId,"APPROVAL_RETENTION_POLICY_PUBLISHED",input.idempotencyKey(),policy.version()+1);
        return repository.policy(actor,scope,policyId,false);
    }
    @Transactional public Record record(UUID requestId) {
        var actor=authority.require("ADMIN.APPROVAL_OPERATIONS:VIEW");var result=eligibility.inspect(actor,authority.scope(),requestId,false);
        authority.require("ADMIN.APPROVAL_OPERATIONS:VIEW");return result;
    }
    @Transactional public Claim claim(UUID requestId,CreateClaim input,ApprovalStepUpHeaders headers) {
        var actor=authority.require("ADMIN.APPROVAL_OPERATIONS:EXECUTE");String scope=authority.scope(),path="/v1/admin/retention/records/"+requestId+"/claims";
        repository.tenant(actor);
        var prior=repository.receipt(actor,scope,path,input.idempotencyKey(),input);
        if(prior!=null) {
            high.verify(actor,RECORD_CLAIM,"approvals.operations.execute","RETENTION_RECORD",requestId,input.expectedVersion(),path,input.idempotencyKey(),input,headers);
            authority.require("ADMIN.APPROVAL_OPERATIONS:EXECUTE");return repository.claim(actor,scope,prior);
        }
        var record=eligibility.inspect(actor,scope,requestId,true);
        if(input.expectedVersion()==null || record.version()!=input.expectedVersion() || !record.policyId().equals(input.policyId())
                || input.expectedPolicyVersion()==null || record.policyVersion()!=input.expectedPolicyVersion()
                || input.expectedHoldVersion()==null || record.holdVersion()!=input.expectedHoldVersion()
                || !record.inventorySha256().equals(input.inventorySha256())) throw ApprovalRetentionErrors.conflict();
        if(!record.claimEligible()) throw ApprovalRetentionErrors.conflict();
        var challenge=high.verify(actor,RECORD_CLAIM,"approvals.operations.execute","RETENTION_RECORD",requestId,record.version(),path,input.idempotencyKey(),input,headers);
        var captured=witnesses.prepare(actor,scope,Operation.CLAIM_RECORD,requestId,input,null,null,challenge,
                repository.policy(actor,scope,record.policyId(),false).published().receiptRetentionDays());
        UUID id=repository.createIntent(actor,record,input,ApprovalDocumentCanonical.sha(headers.challenge()));
        foreign.prepare(actor,id,requestId,record.inventorySha256());authority.require("ADMIN.APPROVAL_OPERATIONS:EXECUTE");high.consume(challenge);
        repository.complete(actor,scope,path,input.idempotencyKey(),input,id,0);witnesses.commit(captured,id,0);
        audit.record(actor,id,"APPROVAL_RETENTION_INTENT_ACCEPTED",input.idempotencyKey(),0);
        return repository.claim(actor,scope,id);
    }
    @Transactional public Claim claim(UUID claimId) {
        var actor=authority.require("ADMIN.APPROVAL_OPERATIONS:VIEW");var result=repository.claim(actor,authority.scope(),claimId);
        authority.require("ADMIN.APPROVAL_OPERATIONS:VIEW");return result;
    }
}
