package com.dwp.services.approval.document;

import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ApprovalDocumentAuthority {
    private final ApprovalWorkAuthority current;
    private final ApprovalIdentityDirectory identities;
    private final ApprovalDocumentOwnerRepository owners;
    private final ApprovalDocumentCanonical canonical;
    public ApprovalDocumentAuthority(ApprovalWorkAuthority current, ApprovalIdentityDirectory identities,
                                     ApprovalDocumentOwnerRepository owners, ApprovalDocumentCanonical canonical) {
        this.current = current; this.identities = identities; this.owners = owners; this.canonical = canonical;
    }

    public ApprovalRequestContext.Actor actor(String permission) { return current.requireCurrent(permission); }

    public void require(ApprovalDocumentOwnerRepository.Owner owner, String action) {
        var actor = actor(owner.resource() + ":VIEW");
        if (!"VIEW".equals(action)) actor(owner.resource() + ":" + action);
        if (ApprovalDecisionRevisionContext.current().isPresent()
                && ApprovalPilotAuthorizationContext.current().isEmpty()) throw ApprovalDocumentCanonical.forbidden();
        if (owner.taskId() == null) {
            if (owner.requesterUserId() != actor.userId()) throw ApprovalDocumentOwnerRepository.hidden();
            return;
        }
        owners.assertTaskState(actor, owner);
        boolean assigned = actor.userId().equals(owner.assigneeUserId());
        boolean candidate = owner.assigneeUserId() == null && owner.candidateRole() != null
                && actor.roles().contains(owner.candidateRole());
        boolean completed = List.of("APPROVED", "REJECTED", "INFO_REQUESTED", "SUPERSEDED").contains(owner.taskStatus());
        if (owner.delegatedFromUserId() != null) {
            if (!assigned || !delegated(actor, owner)) throw ApprovalDocumentOwnerRepository.hidden();
        } else if (!assigned && !candidate && !delegated(actor, owner)) throw ApprovalDocumentOwnerRepository.hidden();
        if (completed && owner.delegatedFromUserId() == null && owner.candidateRole() != null
                && !actor.roles().contains(owner.candidateRole())) throw ApprovalDocumentOwnerRepository.hidden();
        if ("UPDATE".equals(action) && !List.of("PENDING", "CLAIMED", "INFO_REQUESTED").contains(owner.taskStatus())) {
            throw ApprovalDocumentCanonical.forbidden();
        }
    }

    private boolean delegated(ApprovalRequestContext.Actor actor, ApprovalDocumentOwnerRepository.Owner owner) {
        String role = owner.delegatedFromUserId() != null ? owner.authorityRoleCode()
                : owner.assigneeUserId() == null ? owner.candidateRole() : null;
        for (var delegation : owners.lockDelegations(actor, owner)) {
            if (owner.delegatedFromUserId() != null && owner.delegatedFromUserId().longValue() != delegation.sourceUserId()) continue;
            if (role == null) {
                Long source = owner.delegatedFromUserId() == null ? owner.assigneeUserId() : owner.delegatedFromUserId();
                if (source == null || source != delegation.sourceUserId()) continue;
            } else if (!java.util.Arrays.asList(canonical.read(delegation.roles(), String[].class)).contains(role)) continue;
            var subject = identities.require(actor.tenantId(), delegation.sourceUserId());
            if (subject == null) throw ApprovalDocumentCanonical.unavailable("Current delegator authority is unavailable.");
            if (actor.tenantId().equals(subject.tenantId()) && Long.valueOf(delegation.sourceUserId()).equals(subject.userId())
                    && subject.active() && (role == null || subject.hasRole(role))) return true;
        }
        return false;
    }

    public String management(String permission) {
        if (ApprovalDecisionRevisionContext.current().isEmpty() && !ApprovalRequestContext.require().permissions().contains(permission)
                && (permission.endsWith(":VIEW") || permission.endsWith(":UPDATE"))) permission = "ADMIN.APPROVAL_POLICY:MANAGE";
        actor(permission);
        var scope = ApprovalManagementScopeContext.current();
        if (scope.isPresent()) return scope.get().resourceSetKey();
        if (ApprovalDecisionRevisionContext.current().isPresent()) {
            throw ApprovalDocumentCanonical.unavailable("Selected document management scope is unavailable.");
        }
        return "RS_APPROVALS";
    }
}
