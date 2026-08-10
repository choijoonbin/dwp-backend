package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.AuthPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuthPolicyRepository extends JpaRepository<AuthPolicy, Long> {

    Optional<AuthPolicy> findByTenantId(Long tenantId);

    @Query(value = """
            SELECT login_type
              FROM sys_auth_policy_login_types
             WHERE tenant_id = :tenantId
             ORDER BY sort_order, login_type
            """, nativeQuery = true)
    List<String> findAllowedLoginTypes(@Param("tenantId") Long tenantId);
}
