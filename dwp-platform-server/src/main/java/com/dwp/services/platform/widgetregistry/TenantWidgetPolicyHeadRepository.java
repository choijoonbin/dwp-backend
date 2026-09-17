package com.dwp.services.platform.widgetregistry;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface TenantWidgetPolicyHeadRepository extends JpaRepository<TenantWidgetPolicyHead, UUID> {
    Optional<TenantWidgetPolicyHead> findByTenantIdAndDefinitionId(Long tenantId, UUID definitionId);
    List<TenantWidgetPolicyHead> findByTenantId(Long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from TenantWidgetPolicyHead h where h.tenantId = :tenantId and h.definitionId = :definitionId")
    Optional<TenantWidgetPolicyHead> lock(
            @Param("tenantId") Long tenantId, @Param("definitionId") UUID definitionId);
}
