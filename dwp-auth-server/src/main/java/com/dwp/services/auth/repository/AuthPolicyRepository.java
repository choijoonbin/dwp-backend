package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.AuthPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthPolicyRepository extends JpaRepository<AuthPolicy, Long> {

    Optional<AuthPolicy> findByTenantId(Long tenantId);
}
