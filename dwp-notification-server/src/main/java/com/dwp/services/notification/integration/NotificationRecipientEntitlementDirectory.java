package com.dwp.services.notification.integration;

import java.util.List;
import java.util.Optional;

public interface NotificationRecipientEntitlementDirectory {

    Optional<Subject> find(long tenantId, long userId);

    record Subject(
            Long tenantId,
            Long userId,
            String status,
            String identityPlane,
            List<String> permissionKeys) {
    }
}
