package com.dwp.services.approval.integration;

import java.util.List;
import java.util.UUID;

public interface ApprovalIdentityDirectory {

    Subject require(long tenantId, long userId);

    List<Subject> search(long tenantId, String query, int limit);

    record Subject(
            Long tenantId,
            Long userId,
            UUID publicId,
            UUID personPublicId,
            String displayName,
            String email,
            String jobTitle,
            String status,
            List<String> roles) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }

        public boolean hasRole(String roleCode) {
            return roleCode != null && roles != null && roles.contains(roleCode);
        }
    }
}
