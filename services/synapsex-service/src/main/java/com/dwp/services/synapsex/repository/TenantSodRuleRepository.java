package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.TenantSodRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantSodRuleRepository extends JpaRepository<TenantSodRule, Long> {

    List<TenantSodRule> findByTenantIdOrderByRuleKeyAsc(Long tenantId);

    Optional<TenantSodRule> findByTenantIdAndRuleKey(Long tenantId, String ruleKey);

    boolean existsByTenantId(Long tenantId);
}
