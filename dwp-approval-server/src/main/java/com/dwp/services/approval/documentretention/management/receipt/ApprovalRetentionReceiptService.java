package com.dwp.services.approval.documentretention.management.receipt;

import com.dwp.services.approval.documentretention.management.ApprovalRetentionAuthority;
import com.dwp.services.approval.documentretention.management.ApprovalRetentionErrors;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.dwp.services.approval.documentretention.management.receipt.ApprovalRetentionCommandProfile.Operation;

@Service
public class ApprovalRetentionReceiptService {
    private static final String RECEIPT_PROFILE =
            "approval.retention.command-receipt.original-authority.v1";
    private static final String RECEIPT_PREDICATE =
            "predicate.approval.retention-command-original-authority.v1";
    private final ApprovalRetentionAuthority authority;
    private final ApprovalRetentionReceiptRepository repository;
    public ApprovalRetentionReceiptService(ApprovalRetentionAuthority authority,ApprovalRetentionReceiptRepository repository) {
        this.authority=authority;this.repository=repository;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ApprovalRetentionCommandReceipt read(Operation operation,UUID target,String key) {
        key(key);String route=route(operation);
        var actor=authority.require(operation.permission());String scope=authority.scope();exact(route,operation);
        var result=repository.read(actor,scope,operation,target,key);
        var after=authority.require(operation.permission());exact(route,operation);
        if(!actor.userId().equals(after.userId()) || !actor.tenantId().equals(after.tenantId())
                || !scope.equals(authority.scope())) throw ApprovalRetentionErrors.forbidden();
        return result;
    }
    public static void key(String key) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{1,128}") || key.equals(".") || key.equals("..")) throw ApprovalRetentionErrors.invalid();
    }
    public static String route(Operation operation) {
        return switch(operation) {
            case INITIALIZE_POLICY -> "route.approvals.admin.retention-policy-initialization-command.data";
            case SAVE_POLICY -> "route.approvals.admin.retention-policy-draft-command.data";
            case PUBLISH_POLICY -> "route.approvals.admin.retention-policy-publication-command.data";
            case CLAIM_RECORD -> "route.approvals.admin.retention-record-command.data";
        };
    }
    private void exact(String route,Operation operation) {
        ApprovalDecisionRevisionContext.current().ifPresent(evidence->{
            var authorities=ApprovalPilotAuthorizationContext.current()
                    .orElseThrow(ApprovalRetentionErrors::unavailable);
            if(!route.equals(evidence.routeContractKey()) || authorities.size()!=1) {
                throw ApprovalRetentionErrors.forbidden();
            }
            var authority=authorities.getFirst();
            if(!route.equals(authority.routeContractKey()) || !"DATA".equals(authority.routeKind())
                    || !RECEIPT_PROFILE.equals(authority.profileKey()) || !authority.readOnly()
                    || !Set.of(RECEIPT_PREDICATE).equals(authority.predicatePolicyKeys())
                    || !operation.capability().equals(authority.capabilityContractKey())) {
                throw ApprovalRetentionErrors.forbidden();
            }
        });
    }
}
