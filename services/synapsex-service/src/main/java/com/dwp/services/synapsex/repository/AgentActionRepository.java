package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.AgentAction;
import com.dwp.services.synapsex.entity.AgentActionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface AgentActionRepository extends JpaRepository<AgentAction, Long> {

    List<AgentAction> findByTenantIdAndCaseId(Long tenantId, Long caseId);

    /** Lineage 그래프: 전표에 연결된 여러 케이스의 액션 일괄 조회 */
    List<AgentAction> findByTenantIdAndCaseIdIn(Long tenantId, Collection<Long> caseIds);

    long countByTenantIdAndCaseId(Long tenantId, Long caseId);

    /** Phase A: 동일 caseId+actionType에 open 상태 proposal 존재 여부 (중복 제안 방지) */
    boolean existsByTenantIdAndCaseIdAndActionTypeAndStatusIn(
            Long tenantId, Long caseId, String actionType, List<AgentActionStatus> statuses);

    List<AgentAction> findByTenantIdAndExecutedAtIsNotNull(Long tenantId);

    List<AgentAction> findByTenantIdAndCreatedAtAfter(Long tenantId, Instant since);

    List<AgentAction> findByTenantId(Long tenantId);
}
