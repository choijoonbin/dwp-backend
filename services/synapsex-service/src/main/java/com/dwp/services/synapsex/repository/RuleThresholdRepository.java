package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.RuleThreshold;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleThresholdRepository extends JpaRepository<RuleThreshold, Long> {

    List<RuleThreshold> findByTenantIdAndProfileId(Long tenantId, Long profileId);

    Page<RuleThreshold> findByTenantId(Long tenantId, Pageable pageable);

    Page<RuleThreshold> findByTenantIdAndProfileId(Long tenantId, Long profileId, Pageable pageable);

    Page<RuleThreshold> findByTenantIdAndWaers(Long tenantId, String waers, Pageable pageable);

    long countByTenantIdAndProfileId(Long tenantId, Long profileId);
}
