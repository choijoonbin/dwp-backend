package com.dwp.services.platform.mail;

import java.util.List;

interface MailMemberDirectory {

    MemberIdentity requireActive(long tenantId, long userId);

    List<MemberIdentity> searchActive(long tenantId, String query, int limit);

    record MemberIdentity(
            Long tenantId,
            Long userId,
            String displayName,
            String department,
            String email,
            String status,
            String identityPlane) {

        boolean activeTenantUser(long expectedTenantId, long expectedUserId) {
            return tenantId != null && tenantId == expectedTenantId
                    && userId != null && userId == expectedUserId
                    && "ACTIVE".equalsIgnoreCase(status)
                    && "TENANT".equalsIgnoreCase(identityPlane)
                    && displayName != null && !displayName.isBlank();
        }

        boolean activeTenantUser(long expectedTenantId) {
            return userId != null && activeTenantUser(expectedTenantId, userId);
        }
    }
}
