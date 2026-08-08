package com.dwp.services.platform.reference;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface ReferenceSetRepository
        extends JpaRepository<ReferenceSet, Long>, JpaSpecificationExecutor<ReferenceSet> {

    Optional<ReferenceSet> findByTenantIdAndSetKey(Long tenantId, String setKey);

    boolean existsByTenantIdAndSetKey(Long tenantId, String setKey);
}
