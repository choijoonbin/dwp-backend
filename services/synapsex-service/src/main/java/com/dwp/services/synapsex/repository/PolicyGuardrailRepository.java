package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.PolicyGuardrail;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PolicyGuardrailRepository extends JpaRepository<PolicyGuardrail, Long> {

    List<PolicyGuardrail> findByTenantIdAndIsEnabledTrueOrderByGuardrailIdAsc(Long tenantId);

    List<PolicyGuardrail> findByTenantIdOrderByGuardrailIdAsc(Long tenantId);

    Page<PolicyGuardrail> findByTenantIdOrderByGuardrailIdAsc(Long tenantId, Pageable pageable);

    Page<PolicyGuardrail> findByTenantIdAndIsEnabledTrueOrderByGuardrailIdAsc(Long tenantId, Pageable pageable);
}
