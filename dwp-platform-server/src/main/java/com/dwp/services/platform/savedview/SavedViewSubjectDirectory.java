package com.dwp.services.platform.savedview;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface SavedViewSubjectDirectory {

    Subject require(Long tenantId, Long userId);

    List<DirectorySubject> search(
            Long tenantId, String query, boolean activeOnly, int limit);

    record Subject(
            Long tenantId,
            Long userId,
            UUID publicId,
            UUID personPublicId,
            String displayName,
            String email,
            String jobTitle,
            String status,
            String identityPlane,
            List<String> roles,
            List<UUID> groupRefs,
            List<String> permissionKeys) {

        public boolean active() {
            return "ACTIVE".equalsIgnoreCase(status);
        }

        public boolean tenantPlane() {
            return "TENANT".equalsIgnoreCase(identityPlane);
        }

        public boolean hasAnyRole(Iterable<String> accepted) {
            if (roles == null || roles.isEmpty()) return false;
            for (String role : roles) {
                if (role == null) continue;
                String normalized = role.strip().toUpperCase(Locale.ROOT);
                for (String candidate : accepted) {
                    if (normalized.equals(candidate)) return true;
                }
            }
            return false;
        }

        public boolean belongsTo(UUID groupRef) {
            return groupRef != null && groupRefs != null && groupRefs.contains(groupRef);
        }

        public boolean hasPermission(String permissionKey) {
            if (permissionKey == null || permissionKeys == null) return false;
            return permissionKeys.stream()
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(permissionKey::equalsIgnoreCase);
        }
    }

    record DirectorySubject(
            Long tenantId,
            Long userId,
            UUID publicId,
            UUID personPublicId,
            String displayName,
            String email,
            String jobTitle,
            String status,
            String identityPlane,
            List<String> roles,
            List<UUID> groupRefs,
            List<String> permissionKeys) {

        boolean hasCompleteEligibilityEvidence() {
            return roles != null && groupRefs != null && permissionKeys != null;
        }

        Subject exactSnapshot() {
            return new Subject(
                    tenantId, userId, publicId, personPublicId, displayName,
                    email, jobTitle, status, identityPlane,
                    roles, groupRefs, permissionKeys);
        }
    }
}
