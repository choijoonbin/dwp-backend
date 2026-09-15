package com.dwp.services.approval.policy;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class ApprovalPolicyDraftService {
    private final ApprovalPolicyDraftRepository repository;
    private final ApprovalIdentityDirectory identities;
    private final AuditOutboxRecorder audit;

    public ApprovalPolicyDraftService(
            ApprovalPolicyDraftRepository repository,
            ApprovalIdentityDirectory identities,
            AuditOutboxRecorder audit) {
        this.repository = repository;
        this.identities = identities;
        this.audit = audit;
    }

    @Transactional
    public ApprovalDtos.PolicySummary create(
            ApprovalPolicyDraftDtos.Create request,
            String idempotencyKey,
            String correlationId) {
        if (idempotencyKey == null
                || !idempotencyKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "A valid idempotency key is required.");
        }
        ApprovalRequestContext.Actor actor = ApprovalRequestContext.require();
        requireCurrentAuthority(actor);
        ApprovalPolicyDraftRepository.Result result = repository.create(actor, request, idempotencyKey);
        if (result.created()) {
            ApprovalDtos.PolicySummary policy = result.policy();
            audit.record(AuditEvent.builder()
                    .tenantId(actor.tenantId())
                    .category("ADMIN_CHANGE")
                    .action("approval.policy.draft.created")
                    .outcome("SUCCESS")
                    .severity("INFO")
                    .actorType("USER")
                    .actorId(actor.userId().toString())
                    .actorRoles(List.copyOf(actor.roles()))
                    .sourceService("dwp-approval-server")
                    .sourceModule("approval-policy-governance")
                    .targetType("APPROVAL_POLICY")
                    .targetId(policy.policyId().toString())
                    .correlationId(correlationId)
                    .afterState(Map.of(
                            "policyKey", policy.policyKey(),
                            "policyType", policy.policyType(),
                            "pendingLifecycleState", policy.pendingLifecycleState()))
                    .retentionClass("EXTENDED")
                    .build());
        }
        return result.policy();
    }

    private void requireCurrentAuthority(ApprovalRequestContext.Actor actor) {
        ApprovalIdentityDirectory.Subject current;
        try {
            current = identities.require(actor.tenantId(), actor.userId());
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Current Approval policy authority is unavailable.", exception);
        }
        if (current == null || !current.active()
                || !actor.tenantId().equals(current.tenantId())
                || !actor.userId().equals(current.userId())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Current Approval policy authority is not active.");
        }
        if (current.roles() != null
                && current.roles().stream().anyMatch(role -> role.startsWith("PROVIDER_"))) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Provider authority cannot create tenant Approval policies.");
        }
        if (!current.hasPermission("ADMIN.APPROVAL_POLICY:UPDATE")
                && !current.hasPermission("ADMIN.APPROVAL_POLICY:MANAGE")) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Current Approval policy update authority is required.");
        }
    }
}
