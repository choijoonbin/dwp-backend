package com.dwp.services.platform.personalsettings;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonalSettingsWorkspaceStateRepository extends JpaRepository<
        PersonalSettingsWorkspaceState, PersonalSettingsWorkspaceStateId> {
}
