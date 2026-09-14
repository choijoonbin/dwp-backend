package com.dwp.services.approval.security;

import java.time.Instant;
import java.util.UUID;

/** Dedicated signed current-command admission. No default bean, USER source grant or unsigned header fallback exists. */
public interface ApprovalWorkflowQuorumCommandProof {
    enum Purpose { TASK_INFORMATION, REQUEST_REPLY }
    record Verified(long tenantId, long actorUserId, UUID actorPersonId, Purpose purpose, UUID targetId,
            String method, String path, String idempotencyKey, String rawBodySha256, String revision,
            Instant evaluatedAt, Instant expiresAt, ApprovalDecisionRevisionContext.Evidence context, String accessMode) { }
    Verified verify(ApprovalRequestContext.Actor serverActor, Purpose purpose, UUID target,
            String method, String path, String originalKey, byte[] originalBody);
}
