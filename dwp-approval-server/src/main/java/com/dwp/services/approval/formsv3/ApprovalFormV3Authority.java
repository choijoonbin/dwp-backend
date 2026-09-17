package com.dwp.services.approval.formsv3;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Revalidates the current tenant design authority for non-HTTP V3 application services. */
@Component
public final class ApprovalFormV3Authority {
    private final ApprovalIdentityDirectory identities;

    public ApprovalFormV3Authority(ApprovalIdentityDirectory identities) {
        this.identities = identities;
    }

    public Access require(String... actions) {
        ApprovalRequestContext.Actor actor;
        String scope;
        try {
            actor = ApprovalRequestContext.require();
            scope = ApprovalManagementScopeContext.requireResourceSetKey();
        } catch (IllegalStateException exception) {
            throw unavailable();
        }
        if (actor.tenantId() == null || actor.userId() == null || actor.tenantId() <= 0 || actor.userId() <= 0
                || actor.roles().stream().anyMatch(role -> role.startsWith("PROVIDER_"))) {
            throw forbidden();
        }
        ApprovalIdentityDirectory.Subject current;
        try {
            current = identities.require(actor.tenantId(), actor.userId());
        } catch (BaseException exception) {
            if (exception.getErrorCode() == ErrorCode.NOT_FOUND
                    || exception.getErrorCode() == ErrorCode.FORBIDDEN) throw forbidden();
            throw unavailable();
        } catch (RuntimeException exception) {
            throw unavailable();
        }
        if (current == null || !current.active() || !actor.tenantId().equals(current.tenantId())
                || !actor.userId().equals(current.userId()) || current.roles() == null
                || current.roles().stream().anyMatch(role -> role.startsWith("PROVIDER_"))) {
            throw forbidden();
        }
        for (String action : actions) {
            String permission = "ADMIN.APPROVAL_DESIGN:" + action;
            if (!actor.permissions().contains(permission) && !actor.permissions().contains("ADMIN.APPROVAL_DESIGN:MANAGE")
                    || !current.hasPermission(permission) && !current.hasPermission("ADMIN.APPROVAL_DESIGN:MANAGE")) {
                throw forbidden();
            }
        }
        return new Access(actor, scope, Set.copyOf(java.util.Arrays.asList(actions)));
    }

    public void unchanged(Access before, String... actions) {
        if (!before.equals(require(actions))) throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT);
    }

    public record Access(ApprovalRequestContext.Actor actor, String resourceSetKey, Set<String> actions) { }

    private BaseException forbidden() {
        return new BaseException(ErrorCode.FORBIDDEN, "Current Approval design authority is required.");
    }

    private BaseException unavailable() {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "Current Approval design authority cannot be revalidated.");
    }
}
