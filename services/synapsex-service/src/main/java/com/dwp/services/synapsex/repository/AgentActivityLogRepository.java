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
}
