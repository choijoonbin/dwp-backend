package com.dwp.services.platform.reference;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReferenceItemRepository extends JpaRepository<ReferenceItem, Long> {

    List<ReferenceItem> findByTenantIdAndReferenceSetIdOrderBySortOrderAscCodeAsc(
            Long tenantId,
            Long referenceSetId);

    Optional<ReferenceItem> findByTenantIdAndReferenceSetIdAndCode(
            Long tenantId,
            Long referenceSetId,
            String code);

    Optional<ReferenceItem> findByTenantIdAndReferenceSetIdAndReferenceItemId(
            Long tenantId,
            Long referenceSetId,
            Long referenceItemId);

    boolean existsByTenantIdAndReferenceSetIdAndCode(
            Long tenantId,
            Long referenceSetId,
            String code);

    boolean existsByTenantIdAndReferenceSetIdAndParentReferenceItemIdAndLifecycleState(
            Long tenantId,
            Long referenceSetId,
            Long parentReferenceItemId,
            ReferenceLifecycle lifecycleState);

    long countByTenantIdAndReferenceSetId(Long tenantId, Long referenceSetId);

    @Query("""
            select item from ReferenceItem item
             where item.tenantId = :tenantId
               and item.referenceSetId = :referenceSetId
               and item.lifecycleState = com.dwp.services.platform.reference.ReferenceLifecycle.ACTIVE
               and (item.validFrom is null or item.validFrom <= :now)
               and (item.validTo is null or item.validTo > :now)
             order by item.sortOrder asc, item.code asc
            """)
    List<ReferenceItem> findRuntimeItems(
            @Param("tenantId") Long tenantId,
            @Param("referenceSetId") Long referenceSetId,
            @Param("now") Instant now);
}
