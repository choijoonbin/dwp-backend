package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashSet;

@Component
public class ApprovalWorkAuthority {
    private final ApprovalIdentityDirectory identities;

    public ApprovalWorkAuthority(ApprovalIdentityDirectory identities) {
        this.identities = identities;
    }

    public ApprovalRequestContext.Actor require(String permission, boolean ownedRequest) {
        ApprovalDecisionRevisionContext.current().ifPresent(evidence -> {
            if (!OffsetDateTime.now().isBefore(evidence.validUntil())
                    || ApprovalPilotAuthorizationContext.current().isEmpty()) throw unavailable();
            if (ownedRequest && !ApprovalPilotAuthorizationContext.requiresPredicate(
                    "predicate.approval.own-request.v1")) throw forbidden();
            if (!ownedRequest && "ACTION.APPROVAL_TASK:VIEW".equals(permission)
                    && !ApprovalPilotAuthorizationContext.requiresPredicate("predicate.approval-task-readable.v1")) throw forbidden();
        });
        return requireCurrent(permission);
    }

    public ApprovalRequestContext.Actor requireCurrent(String permission) {
        var actor = ApprovalRequestContext.require();
        ApprovalDecisionRevisionContext.current().ifPresent(evidence -> {
            if (!OffsetDateTime.now().isBefore(evidence.validUntil())) throw unavailable();
        });
        var subject = identities.require(actor.tenantId(), actor.userId());
        if (subject == null) throw unavailable();
        if (!actor.tenantId().equals(subject.tenantId())
                || !actor.userId().equals(subject.userId()) || !subject.active()
                || !subject.hasPermission("APP.APPROVALS:VIEW")
                || !subject.hasPermission(permission)
                || !actor.permissions().contains(permission)) throw forbidden();
        if (actor.roles().stream().anyMatch(role -> role.startsWith("PROVIDER_"))
                || (subject.roles() != null && subject.roles().stream().anyMatch(role -> role.startsWith("PROVIDER_")))) throw forbidden();
        if (actor.personPublicId() != null
                && !actor.personPublicId().equals(subject.personPublicId())) throw forbidden();
        var roles = new HashSet<>(actor.roles());
        roles.retainAll(subject.roles() == null ? java.util.List.of() : subject.roles());
        return new ApprovalRequestContext.Actor(actor.userId(), actor.tenantId(),
                actor.personPublicId(), actor.displayName(), roles, actor.permissions());
    }

    private BaseException unavailable() {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "Current exact Approval work authority is unavailable.");
    }

    private BaseException forbidden() {
        return new BaseException(ErrorCode.FORBIDDEN, "Current Approval work authority was revoked.");
    }
}
