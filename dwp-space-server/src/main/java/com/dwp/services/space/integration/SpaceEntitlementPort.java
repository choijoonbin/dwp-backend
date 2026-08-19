package com.dwp.services.space.integration;

import java.time.Instant;

public interface SpaceEntitlementPort {

    Result synchronize(Command command);

    ValidationResult validatePrincipal(ValidationCommand command);

    boolean configured();

    record Command(
            long tenantId,
            String sourceRef,
            String correlationId,
            String principalType,
            String principalRef,
            String resourceKey,
            String resourceName,
            String permissionCode,
            String action,
            Instant validUntil,
            String justification,
            long actorUserId) {
    }

    record Result(
            String grantId,
            String lifecycleState,
            long version,
            boolean changed) {
    }

    record ValidationCommand(
            long tenantId,
            String correlationId,
            String principalType,
            String principalRef,
            long actorUserId) {
    }

    record ValidationResult(
            String principalType,
            String suppliedRef,
            String canonicalRef,
            boolean active) {
    }
}
