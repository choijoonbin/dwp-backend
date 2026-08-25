package com.dwp.services.platform.home.personalization;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HomeViewRepository extends JpaRepository<HomeView, UUID> {
    List<HomeView> findByTenantIdAndUserIdAndSurfaceKeyOrderByUpdatedAtDesc(
            Long tenantId, Long userId, String surfaceKey);

    Optional<HomeView> findByViewIdAndTenantIdAndUserId(
            UUID viewId, Long tenantId, Long userId);

    Optional<HomeView> findByTenantIdAndUserIdAndSurfaceKeyAndViewKey(
            Long tenantId, Long userId, String surfaceKey, String viewKey);

    long countByTenantIdAndUserIdAndSurfaceKey(Long tenantId, Long userId, String surfaceKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select view from HomeView view where view.viewId = :viewId "
            + "and view.tenantId = :tenantId and view.userId = :userId")
    Optional<HomeView> findOwnedForUpdate(UUID viewId, Long tenantId, Long userId);
}
