package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.TenantCurrencyScope;
import com.dwp.services.synapsex.entity.TenantCurrencyScopeId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantCurrencyScopeRepository extends JpaRepository<TenantCurrencyScope, TenantCurrencyScopeId> {

    List<TenantCurrencyScope> findByTenantIdOrderByWaersAsc(Long tenantId);

    Optional<TenantCurrencyScope> findByTenantIdAndWaers(Long tenantId, String waers);

    boolean existsByTenantId(Long tenantId);
}
