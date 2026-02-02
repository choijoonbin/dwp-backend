package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.TenantCompanyCodeScope;
import com.dwp.services.synapsex.entity.TenantCompanyCodeScopeId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantCompanyCodeScopeRepository extends JpaRepository<TenantCompanyCodeScope, TenantCompanyCodeScopeId> {

    List<TenantCompanyCodeScope> findByTenantIdOrderByBukrsAsc(Long tenantId);

    Optional<TenantCompanyCodeScope> findByTenantIdAndBukrs(Long tenantId, String bukrs);

    boolean existsByTenantId(Long tenantId);
}
