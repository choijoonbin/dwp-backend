package com.dwp.services.platform.reference;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ReferenceItemLabelRepository extends JpaRepository<ReferenceItemLabel, Long> {

    List<ReferenceItemLabel> findByTenantIdAndReferenceItemIdOrderByLocaleAsc(
            Long tenantId,
            Long referenceItemId);

    List<ReferenceItemLabel> findByTenantIdAndReferenceItemIdIn(
            Long tenantId,
            Collection<Long> referenceItemIds);

    long countByTenantIdAndReferenceItemId(Long tenantId, Long referenceItemId);

    void deleteByTenantIdAndReferenceItemId(Long tenantId, Long referenceItemId);
}
