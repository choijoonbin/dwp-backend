package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.AgentAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentActionRepository extends JpaRepository<AgentAction, Long> {

    List<AgentAction> findByTenantIdAndCaseId(Long tenantId, Long caseId);

    long countByTenantIdAndCaseId(Long tenantId, Long caseId);

    List<AgentAction> findByTenantIdAndExecutedAtIsNotNull(Long tenantId);
}
