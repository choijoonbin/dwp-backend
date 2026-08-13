package com.dwp.services.platform.workspace;

import java.time.OffsetDateTime;

public interface AppEntitlementProvisioner {

    Result synchronize(Command command);

    record Command(
            Long tenantId,
            String sourceRef,
            Long userId,
            String resourceKey,
            String permissionCode,
            String action,
            OffsetDateTime validTo,
            Long actorId,
            String justification,
            String correlationId) {
    }

    record Result(
            String grantId,
            String lifecycleState,
            long version,
            boolean changed) {
    }

    final class ProvisioningException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ProvisioningException(String message) {
            super(message);
        }

        public ProvisioningException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
