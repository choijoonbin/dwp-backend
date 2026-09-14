package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalRequestContext;

/** Compatibility guard retained only for rollout states 000/100. */
final class ApprovalLegacyDelegationGuard {

    private ApprovalLegacyDelegationGuard() {
    }

    static void verify(
            ApprovalRequestContext.Actor actor,
            ApprovalQueryRepository.TaskAccess task,
            ApprovalIdentityDirectory identities) {
        if (task.delegatedFromUserId() == null) return;
        ApprovalIdentityDirectory.Subject delegator = identities.require(
                actor.tenantId(), task.delegatedFromUserId());
        String authorityRoleCode = task.delegatedAuthorityRoleCode() != null
                ? task.delegatedAuthorityRoleCode()
                : task.delegatedAccess() && task.assigneeUserId() == null
                        ? task.candidateRole()
                        : null;
        boolean roleBasedAuthority = authorityRoleCode != null;
        if (!delegator.active()
                || (roleBasedAuthority
                && !delegator.hasRole(authorityRoleCode))) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The original approver no longer holds the delegated authority.");
        }
    }
}
