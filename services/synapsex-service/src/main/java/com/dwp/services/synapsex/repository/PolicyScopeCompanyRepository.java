package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.PolicyScopeCompany;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PolicyScopeCompanyRepository extends JpaRepository<PolicyScopeCompany, Long> {

    List<PolicyScopeCompany> findByTenantIdAndProfileId(Long tenantId, Long profileId);

    Optional<PolicyScopeCompany> findByTenantIdAndProfileIdAndBukrs(Long tenantId, Long profileId, String bukrs);

    List<PolicyScopeCompany> findByTenantIdAndProfileIdAndIncludedTrue(Long tenantId, Long profileId);

    @Query("SELECT MAX(p.updatedAt) FROM PolicyScopeCompany p WHERE p.tenantId = :tenantId AND p.profileId = :profileId")
    Optional<Instant> findMaxUpdatedAtByTenantIdAndProfileId(@Param("tenantId") Long tenantId, @Param("profileId") Long profileId);
}
