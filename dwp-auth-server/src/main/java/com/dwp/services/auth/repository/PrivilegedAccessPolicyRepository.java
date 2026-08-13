package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.PrivilegedAccessPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PrivilegedAccessPolicyRepository
        extends JpaRepository<PrivilegedAccessPolicy, Long> {

    List<PrivilegedAccessPolicy> findByTenantIdOrderByRoleIdAsc(Long tenantId);

    Optional<PrivilegedAccessPolicy> findByTenantIdAndRoleId(Long tenantId, Long roleId);
}
