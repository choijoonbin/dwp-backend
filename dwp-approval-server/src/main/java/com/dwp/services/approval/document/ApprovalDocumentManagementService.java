package com.dwp.services.approval.document;

import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.document.ApprovalDocumentDtos.*;

@Service
public class ApprovalDocumentManagementService {
    public static final String POLICY_PUBLISH = "route.approvals.admin.document-policy-publish.action";
    public static final String HOLD_PUBLISH = "route.approvals.admin.document-hold-publish.action";
    private final ApprovalDocumentAuthority authority;
    private final ApprovalDocumentRepository repository;
    private final ApprovalDocumentOwnerRepository owners;
    private final ApprovalDocumentPolicy validator;
    private final ApprovalDocumentPublishGuard publish;
    private final ApprovalDocumentAudit audit;
    public ApprovalDocumentManagementService(ApprovalDocumentAuthority authority, ApprovalDocumentRepository repository,
            ApprovalDocumentOwnerRepository owners, ApprovalDocumentPolicy validator, ApprovalDocumentPublishGuard publish, ApprovalDocumentAudit audit) {
        this.authority = authority; this.repository = repository; this.owners = owners; this.validator = validator;
        this.publish = publish; this.audit = audit;
    }
    @Transactional
    public Policy policy() {
        String scope = authority.management("ADMIN.APPROVAL_POLICY:VIEW");
        var result = repository.policy(ApprovalRequestContext.require(), scope, false);
        authority.management("ADMIN.APPROVAL_POLICY:VIEW"); return result;
    }
    @Transactional
    public Policy save(UUID policyId, SavePolicy input) {
        String scope = authority.management("ADMIN.APPROVAL_POLICY:UPDATE"); var actor = ApprovalRequestContext.require();
        String route = "/v1/admin/document-tools/policies/" + policyId + "/draft";
        var policy = repository.policyForMutation(actor, scope, policyId);
        var prior = repository.receipt(actor, route, input.idempotencyKey(), input);
        if (prior != null) { authority.management("ADMIN.APPROVAL_POLICY:UPDATE"); return policy; }
        if (input.expectedVersion() == null || input.expectedVersion() != policy.version()) throw ApprovalDocumentCanonical.conflict();
        validator.validate(input.rules()); repository.savePolicy(actor, policy, input.rules());
        authority.management("ADMIN.APPROVAL_POLICY:UPDATE");
        var metadata = Map.<String, Object>of("policyId", policy.policyId(), "version", policy.version() + 1);
        repository.complete(actor, route, input.idempotencyKey(), input, metadata, input.rules().evidenceRetentionDays());
        audit.record(actor, policy.policyId(), "APPROVAL_DOCUMENT_POLICY_DRAFT_SAVED", input.idempotencyKey(), metadata);
        return repository.policy(actor, scope, true);
    }
    @Transactional
    public Policy publish(UUID policyId, PublishPolicy input, ApprovalStepUpHeaders headers) {
        String scope = authority.management("ADMIN.APPROVAL_POLICY:PUBLISH"); var actor = ApprovalRequestContext.require();
        String path = "/v1/admin/document-tools/policies/" + policyId + "/publish";
        var policy = repository.policyForMutation(actor, scope, policyId);
        var prior = repository.receipt(actor, path, input.idempotencyKey(), input);
        var challenge = publish.verify(actor, POLICY_PUBLISH, "DOCUMENT_POLICY", policy.policyId(),
                input.expectedVersion(), path, input.idempotencyKey(), input, headers);
        if (prior != null) { authority.management("ADMIN.APPROVAL_POLICY:PUBLISH"); return policy; }
        if (input.expectedVersion() != policy.version() || policy.pending() == null) throw ApprovalDocumentCanonical.conflict();
        validator.validate(policy.pending().rules());
        repository.publishPolicy(actor, policy, input.reviewComment());
        authority.management("ADMIN.APPROVAL_POLICY:PUBLISH"); publish.consume(challenge);
        var metadata = Map.<String, Object>of("policyId", policy.policyId(), "version", policy.version() + 1, "revision", policy.pending().revision());
        repository.complete(actor, path, input.idempotencyKey(), input, metadata, policy.pending().rules().evidenceRetentionDays());
        audit.record(actor, policy.policyId(), "APPROVAL_DOCUMENT_POLICY_PUBLISHED", input.idempotencyKey(), metadata);
        return repository.policy(actor, scope, true);
    }
    @Transactional
    public Hold hold(UUID requestId) {
        String scope = authority.management("ADMIN.APPROVAL_POLICY:VIEW"); var actor = ApprovalRequestContext.require();
        var owner = managed(actor, requestId, scope); var policy = repository.policy(actor, scope, false);
        var result = repository.hold(actor, requestId, repository.head(actor, owner.requestId(), policy.published().rules().evidenceRetentionDays()));
        authority.management("ADMIN.APPROVAL_POLICY:VIEW"); return result;
    }
    @Transactional
    public Hold propose(UUID requestId, HoldProposal input) {
        String scope = authority.management("ADMIN.APPROVAL_POLICY:UPDATE"); var actor = ApprovalRequestContext.require();
        String path = "/v1/admin/document-tools/holds/" + requestId + "/proposals";
        var prior = repository.receipt(actor, path, input.idempotencyKey(), input);
        managed(actor, requestId, scope); var policy = repository.policy(actor, scope, false);
        var head = repository.head(actor, requestId, policy.published().rules().evidenceRetentionDays());
        if (prior != null) { authority.management("ADMIN.APPROVAL_POLICY:UPDATE"); return repository.hold(actor, requestId, head); }
        if (input.expectedVersion() == null || input.expectedVersion() != head.holdVersion()) throw ApprovalDocumentCanonical.conflict();
        repository.proposeHold(actor, requestId, head, input); authority.management("ADMIN.APPROVAL_POLICY:UPDATE");
        var metadata = Map.<String, Object>of("requestId", requestId, "version", head.holdVersion() + 1, "operation", input.operation().name());
        repository.complete(actor, path, input.idempotencyKey(), input, metadata, policy.published().rules().evidenceRetentionDays());
        audit.record(actor, requestId, "APPROVAL_DOCUMENT_HOLD_PROPOSED", input.idempotencyKey(), metadata);
        return repository.hold(actor, requestId, repository.head(actor, requestId, policy.published().rules().evidenceRetentionDays()));
    }
    @Transactional
    public Hold publishHold(UUID requestId, PublishHold input, ApprovalStepUpHeaders headers) {
        String scope = authority.management("ADMIN.APPROVAL_POLICY:PUBLISH"); var actor = ApprovalRequestContext.require();
        String path = "/v1/admin/document-tools/holds/" + requestId + "/publish";
        var prior = repository.receipt(actor, path, input.idempotencyKey(), input);
        managed(actor, requestId, scope); var policy = repository.policy(actor, scope, false);
        var head = repository.head(actor, requestId, policy.published().rules().evidenceRetentionDays());
        var challenge = publish.verify(actor, HOLD_PUBLISH, "DOCUMENT_HOLD", requestId, input.expectedVersion(),
                path, input.idempotencyKey(), input, headers);
        if (prior != null) { authority.management("ADMIN.APPROVAL_POLICY:PUBLISH"); return repository.hold(actor, requestId, head); }
        if (input.expectedVersion() != head.holdVersion()) throw ApprovalDocumentCanonical.conflict();
        repository.publishHold(actor, requestId, head, input); authority.management("ADMIN.APPROVAL_POLICY:PUBLISH"); publish.consume(challenge);
        var metadata = Map.<String, Object>of("requestId", requestId, "version", head.holdVersion() + 1, "proposalId", input.proposalId());
        repository.complete(actor, path, input.idempotencyKey(), input, metadata, policy.published().rules().evidenceRetentionDays());
        audit.record(actor, requestId, "APPROVAL_DOCUMENT_HOLD_PUBLISHED", input.idempotencyKey(), metadata);
        return repository.hold(actor, requestId, repository.head(actor, requestId, policy.published().rules().evidenceRetentionDays()));
    }
    private ApprovalDocumentOwnerRepository.Owner managed(ApprovalRequestContext.Actor actor, UUID request, String scope) {
        var owner = owners.lock(actor, OwnerType.REQUEST, request);
        if (!scope.equals(owner.resourceSetKey())) throw ApprovalDocumentOwnerRepository.hidden();
        return owner;
    }
}
