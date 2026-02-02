package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.TenantScopeSeedState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantScopeSeedStateRepository extends JpaRepository<TenantScopeSeedState, Long> {

    Optional<TenantScopeSeedState> findByTenantId(Long tenantId);
}
