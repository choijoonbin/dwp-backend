package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 테넌트 Repository
 */
@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findByCode(String code);

    List<Tenant> findByTenantIdInAndStatus(java.util.List<Long> tenantIds, String status);
}
