package com.dwp.services.platform.home.preference;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HomePreferenceRepository extends JpaRepository<HomePreference, Long> {

    Optional<HomePreference> findByTenantIdAndUserIdAndSurfaceKey(
            Long tenantId,
            Long userId,
            String surfaceKey);
}
