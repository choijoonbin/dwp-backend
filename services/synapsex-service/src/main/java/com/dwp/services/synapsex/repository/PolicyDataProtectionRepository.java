package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.PolicyDataProtection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PolicyDataProtectionRepository extends JpaRepository<PolicyDataProtection, Long> {

    Optional<PolicyDataProtection> findByTenantIdAndProfileId(Long tenantId, Long profileId);
}
