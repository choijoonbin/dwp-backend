package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.AgentCaseActionHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Phase 6: agent_case_action_history 조회/저장.
 */
public interface AgentCaseActionHistoryRepository extends JpaRepository<AgentCaseActionHistory, Long> {

    Page<AgentCaseActionHistory> findByTenantIdAndCaseIdOrderByActionAtDesc(Long tenantId, Long caseId, Pageable pageable);
}
