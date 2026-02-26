package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.AgentActivityLog;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    /**
     * [사고 과정] 케이스별 AGENT_STREAM 로그만 시간순(occurred_at ASC) 조회.
     */
    List<AgentActivityLog> findByTenantIdAndResourceTypeAndResourceIdAndEventTypeOrderByOccurredAtAsc(
            Long tenantId, String resourceType, String resourceId, String eventType, Pageable pageable);

    /**
     * [추론 탭] 케이스별 AGENT_STREAM 로그만 최신순(occurred_at DESC) 조회.
     */
    List<AgentActivityLog> findByTenantIdAndResourceTypeAndResourceIdAndEventTypeOrderByOccurredAtDesc(
            Long tenantId, String resourceType, String resourceId, String eventType, Pageable pageable);

    /** [이력 탭] 케이스별 전체 활동 로그(모든 event_type) 시간순(occurred_at ASC) 조회.
     */
    List<AgentActivityLog> findByTenantIdAndResourceTypeAndResourceIdOrderByOccurredAtAsc(
            Long tenantId, String resourceType, String resourceId, Pageable pageable);

    @Query(value = """
            SELECT EXISTS (
              SELECT 1
                FROM dwp_aura.agent_activity_log a
               WHERE a.tenant_id = :tenantId
                 AND a.event_type = 'AGENT_EVENT'
                 AND a.resource_type = :resourceType
                 AND a.resource_id = :resourceId
                 AND COALESCE(a.metadata_json->>'run_id','') = COALESCE(:runId,'')
                 AND COALESCE(a.metadata_json->>'event_type','') = COALESCE(:eventType,'')
                 AND COALESCE(a.metadata_json->>'node','') = COALESCE(:node,'')
                 AND COALESCE(a.metadata_json->>'input_hash','') = COALESCE(:inputHash,'')
            )
            """, nativeQuery = true)
    boolean existsAgentEventDuplicate(
            @Param("tenantId") Long tenantId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("runId") String runId,
            @Param("eventType") String eventType,
            @Param("node") String node,
            @Param("inputHash") String inputHash);

    @Query(value = """
            SELECT a.metadata_json->>'run_id'
              FROM dwp_aura.agent_activity_log a
             WHERE a.tenant_id = :tenantId
               AND a.event_type = 'AGENT_EVENT'
               AND a.resource_type = :resourceType
               AND a.resource_id = :resourceId
               AND COALESCE(a.metadata_json->>'run_id', '') <> ''
             ORDER BY a.occurred_at DESC, a.created_at DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<String> findLatestRunIdByAgentEvent(
            @Param("tenantId") Long tenantId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId);
}
