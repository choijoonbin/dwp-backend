package com.dwp.services.platform.savedview;

public interface SavedViewSubjectDirectory {

    Subject require(Long tenantId, Long userId);

    record Subject(
            Long tenantId,
            Long userId,
            String displayName,
            String email,
            String status) {

        public boolean active() {
            return "ACTIVE".equalsIgnoreCase(status);
        }
    }
}
