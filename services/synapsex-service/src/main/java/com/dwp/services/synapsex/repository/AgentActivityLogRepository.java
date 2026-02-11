package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.AgentActivityLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * agent_activity_log 조회
 */
public interface AgentActivityLogRepository extends JpaRepository<AgentActivityLog, Long> {

    List<AgentActivityLog> findByTenantIdAndOccurredAtAfterOrderByOccurredAtDesc(
            Long tenantId, Instant occurredAt, Pageable pageable);

    /**
     * Workbench 타임라인: 케이스별 활동 로그, occurred_at DESC, tenant 격리.
     */
    List<AgentActivityLog> findByTenantIdAndResourceTypeAndResourceIdOrderByOccurredAtDesc(
            Long tenantId, String resourceType, String resourceId, Pageable pageable);

    /**
     * Lineage 그래프: 전표에 연결된 여러 케이스의 활동 로그를 한 번에 조회, occurred_at ASC (시간순).
     */
    List<AgentActivityLog> findByTenantIdAndResourceTypeAndResourceIdInOrderByOccurredAtAsc(
            Long tenantId, String resourceType, List<String> resourceIds);
}
