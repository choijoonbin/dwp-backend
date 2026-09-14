package com.dwp.services.approval.integration;

import java.util.List;
import java.util.UUID;

public interface ApprovalIdentityDirectory {

    Subject require(long tenantId, long userId);

    List<Subject> search(long tenantId, String query, int limit);

    RoleEligibility requireRole(long tenantId, String roleCode);

    record Subject(
            Long tenantId,
            Long userId,
            UUID publicId,
            UUID personPublicId,
            String displayName,
            String email,
            String jobTitle,
            String status,
            List<String> roles,
            List<String> permissionKeys) {

        public Subject(
                Long tenantId,
                Long userId,
                UUID publicId,
                UUID personPublicId,
                String displayName,
                String email,
                String jobTitle,
                String status,
                List<String> roles) {
            this(tenantId, userId, publicId, personPublicId, displayName, email,
                    jobTitle, status, roles, List.of());
        }

        public boolean active() {
            return "ACTIVE".equals(status);
        }

        public boolean hasRole(String roleCode) {
            return roleCode != null && roles != null && roles.contains(roleCode);
        }

        public boolean hasPermission(String permissionKey) {
            return permissionKey != null
                    && permissionKeys != null
                    && permissionKeys.contains(permissionKey);
        }
    }

    record RoleEligibility(
            Long tenantId,
            String roleCode,
            String lifecycleState,
            long eligibleUserCount,
            boolean eligible) {

        public boolean activeAndStaffed() {
            return "ACTIVE".equals(lifecycleState) && eligible && eligibleUserCount > 0;
        }
    }
}
