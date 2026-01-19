package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.AuthPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 인증 정책 Repository
 */
@Repository
public interface AuthPolicyRepository extends JpaRepository<AuthPolicy, Long> {
    
    /**
     * 테넌트 ID로 인증 정책 조회
     */
    Optional<AuthPolicy> findByTenantId(Long tenantId);
}
