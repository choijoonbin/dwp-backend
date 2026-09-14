package com.dwp.services.auth.service;

import java.util.UUID;

/** Immutable owner-sealed mutation pins, independent of either proof verifier implementation. */
public record ApprovalFormReferenceBinding(UUID requestId, long requestVersion, String payloadSha256,
        String idempotencyKey, String method, String path, String requiredPermission) { }
