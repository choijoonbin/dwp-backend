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
import java.util.Set;

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
        if (permission == null || permission.isBlank()) throw forbidden();
        return requireAnyCurrent(Set.of(permission));
    }

    public ApprovalRequestContext.Actor requireAnyCurrent(Set<String> permissions) {
        if (permissions == null || permissions.isEmpty()
                || permissions.stream().anyMatch(permission -> permission == null || permission.isBlank())) {
            throw forbidden();
        }
        ApprovalRequestContext.Actor actor;
        try {
            actor = ApprovalRequestContext.require();
        } catch (IllegalStateException exception) {
            throw unavailable();
        }
        ApprovalDecisionRevisionContext.current().ifPresent(evidence -> {
            if (!OffsetDateTime.now().isBefore(evidence.validUntil())) throw unavailable();
        });
        if (actor.tenantId() == null || actor.userId() == null
                || actor.permissions() == null || actor.roles() == null) {
            throw unavailable();
        }
        var subject = currentSubject(actor);
        if (!actor.tenantId().equals(subject.tenantId())
                || !actor.userId().equals(subject.userId()) || !subject.active()
                || !subject.hasPermission("APP.APPROVALS:VIEW")
                || subject.roles() == null || subject.permissionKeys() == null) throw forbidden();
        if (actor.roles().stream().anyMatch(role -> role.startsWith("PROVIDER_"))
                || subject.roles().stream().anyMatch(role -> role.startsWith("PROVIDER_"))) throw forbidden();
        if (actor.personPublicId() != null
                && !actor.personPublicId().equals(subject.personPublicId())) throw forbidden();
        var roles = new HashSet<>(actor.roles());
        roles.retainAll(subject.roles());
        var currentPermissions = new HashSet<>(actor.permissions());
        currentPermissions.retainAll(subject.permissionKeys());
        if (currentPermissions.stream().noneMatch(permissions::contains)) throw forbidden();
        return new ApprovalRequestContext.Actor(actor.userId(), actor.tenantId(),
                actor.personPublicId(), actor.displayName(),
                Set.copyOf(roles), Set.copyOf(currentPermissions));
    }

    private ApprovalIdentityDirectory.Subject currentSubject(
            ApprovalRequestContext.Actor actor) {
        try {
            ApprovalIdentityDirectory.Subject subject = identities.require(
                    actor.tenantId(), actor.userId());
            if (subject == null) throw unavailable();
            return subject;
        } catch (BaseException exception) {
            if (exception.getErrorCode() == ErrorCode.NOT_FOUND
                    || exception.getErrorCode() == ErrorCode.FORBIDDEN) {
                throw forbidden();
            }
            if (exception.getErrorCode() == ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE) {
                throw exception;
            }
            throw unavailable();
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private BaseException unavailable() {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "Current exact Approval work authority is unavailable.");
    }

    private BaseException forbidden() {
        return new BaseException(ErrorCode.FORBIDDEN, "Current Approval work authority was revoked.");
    }
}
