package com.dwp.services.approval.forms;

import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos.Workspace;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import java.util.UUID;

/** Scoped persistence observation, not a substitute for current management authority. */
@FunctionalInterface
public interface ApprovalFormWorkspaceReadPort {
    Workspace read(Actor actor, UUID formId);
}
