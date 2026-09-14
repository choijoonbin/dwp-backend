package com.dwp.services.approval.domain;

import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import java.util.Map;
import java.util.UUID;

/** The caller supplies locked, server-owned immutable pins, never client-selected schema identity. */
@FunctionalInterface
public interface ApprovalFormPayloadNormalization {
    Map<String, Object> normalize(Actor actor, UUID requestId, UUID formVersionId,
            String formSchemaSha256, String immutableSchema, Map<String, Object> merged,
            boolean submitting, long expectedRequestVersion);
}
