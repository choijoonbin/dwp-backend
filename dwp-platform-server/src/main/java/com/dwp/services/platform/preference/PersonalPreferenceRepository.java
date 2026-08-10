package com.dwp.services.platform.preference;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PersonalPreferenceRepository extends JpaRepository<PersonalPreference, Long> {

    Optional<PersonalPreference> findByTenantIdAndUserId(Long tenantId, Long userId);
}
