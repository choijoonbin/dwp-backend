package com.dwp.services.approval.integration;

import java.util.UUID;

public interface ApprovalRecoveryAuditorResolver {

    Assignment resolve(
            long tenantId,
            UUID outboxId,
            long originatorUserId,
            String resourceSetKey);

    record Assignment(
            long selectedUserId,
            String resourceSetKey,
            String assignmentRevision) {
    }
}
