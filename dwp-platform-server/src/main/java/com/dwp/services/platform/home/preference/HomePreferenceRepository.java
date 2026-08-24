package com.dwp.services.platform.home.preference;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface HomePreferenceRepository extends JpaRepository<HomePreference, Long> {

    Optional<HomePreference> findByTenantIdAndUserIdAndSurfaceKey(
            Long tenantId,
            Long userId,
            String surfaceKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select preference from HomePreference preference
             where preference.tenantId = :tenantId
               and preference.userId = :userId
               and preference.surfaceKey = :surfaceKey
            """)
    Optional<HomePreference> findForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId,
            @Param("surfaceKey") String surfaceKey);
}
