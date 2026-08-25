package com.dwp.services.auth.service;

import com.dwp.core.exception.BaseException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Persists governed mutation denials independently before preserving the API error. */
final class AppGovernanceDenialAudit {

    private final IdentityAuditService audit;

    AppGovernanceDenialAudit(IdentityAuditService audit) {
        this.audit = audit;
    }

    <T> T capture(
            Long tenantId,
            Long actorId,
            String correlationId,
            String action,
            String targetType,
            String targetId,
            Supplier<T> mutation) {
        try {
            return mutation.get();
        } catch (BaseException exception) {
            Map<String, Object> attempted = new LinkedHashMap<>();
            attempted.put("errorCode", exception.getErrorCode().name());
            attempted.put("operation", action);
            audit.denied(
                    tenantId, actorId, action, targetType, targetId, correlationId,
                    exception.getMessage(), Map.copyOf(attempted));
            throw exception;
        }
    }
}
