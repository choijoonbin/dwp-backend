package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.PolicySodRule;
import com.dwp.services.synapsex.entity.PolicySodRuleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PolicySodRuleRepository extends JpaRepository<PolicySodRule, PolicySodRuleId> {

    List<PolicySodRule> findByTenantIdAndProfileIdOrderByRuleKeyAsc(Long tenantId, Long profileId);

    Optional<PolicySodRule> findByTenantIdAndProfileIdAndRuleKey(Long tenantId, Long profileId, String ruleKey);

    boolean existsByTenantIdAndProfileIdAndRuleKey(Long tenantId, Long profileId, String ruleKey);

    @Query("SELECT MAX(r.updatedAt) FROM PolicySodRule r WHERE r.tenantId = :tenantId AND r.profileId = :profileId")
    Optional<Instant> findMaxUpdatedAtByTenantIdAndProfileId(@Param("tenantId") Long tenantId, @Param("profileId") Long profileId);
}
