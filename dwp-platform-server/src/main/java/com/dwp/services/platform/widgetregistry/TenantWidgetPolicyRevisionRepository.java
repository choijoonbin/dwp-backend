package com.dwp.services.platform.widgetregistry;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface TenantWidgetPolicyRevisionRepository extends JpaRepository<TenantWidgetPolicyRevision, UUID> {
    List<TenantWidgetPolicyRevision> findByTenantIdAndDefinitionIdOrderByRevisionNumberDesc(
            Long tenantId, UUID definitionId);
    Optional<TenantWidgetPolicyRevision> findByPolicyRevisionIdAndTenantId(
            UUID revisionId, Long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from TenantWidgetPolicyRevision p where p.policyRevisionId = :id and p.tenantId = :tenantId")
    Optional<TenantWidgetPolicyRevision> lock(
            @Param("id") UUID id, @Param("tenantId") Long tenantId);
}
