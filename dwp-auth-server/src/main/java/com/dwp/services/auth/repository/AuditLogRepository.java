package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * 감사 로그 Repository
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    
    /**
     * 테넌트 ID와 액션 타입으로 조회
     */
    Page<AuditLog> findByTenantIdAndActionOrderByCreatedAtDesc(
            Long tenantId, String action, Pageable pageable);
    
    /**
     * 테넌트 ID와 리소스 타입으로 조회
     */
    Page<AuditLog> findByTenantIdAndResourceTypeOrderByCreatedAtDesc(
            Long tenantId, String resourceType, Pageable pageable);
    
    /**
     * 테넌트 ID와 기간으로 조회
     */
    @Query("SELECT a FROM AuditLog a " +
           "WHERE a.tenantId = :tenantId " +
           "AND a.createdAt >= :from " +
           "AND a.createdAt <= :to " +
           "ORDER BY a.createdAt DESC")
    Page<AuditLog> findByTenantIdAndCreatedAtBetween(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);
    
    /**
     * PR-08A: 감사 로그 필터링 조회
     * 
     * Note: 
     * 1. Hibernate가 action, resourceType을 bytea로 인식하는 문제로 Native Query 사용
     * 2. CAST를 사용하여 명시적으로 VARCHAR로 변환
     * 3. LocalDateTime 파라미터는 TO_TIMESTAMP 함수로 변환하여 처리
     * 4. metadataJson에 대한 keyword 검색은 제외 (TEXT 타입, 검색 시 성능 이슈)
     */
    @Query(value = "SELECT a.audit_log_id, a.tenant_id, a.actor_user_id, a.action, a.resource_type, " +
           "a.resource_id, a.metadata_json, a.created_at, a.created_by, a.updated_at, a.updated_by " +
           "FROM com_audit_logs a " +
           "WHERE a.tenant_id = :tenantId " +
           "AND (:fromStr IS NULL OR :fromStr = '' OR a.created_at >= TO_TIMESTAMP(:fromStr, 'YYYY-MM-DD HH24:MI:SS')) " +
           "AND (:toStr IS NULL OR :toStr = '' OR a.created_at <= TO_TIMESTAMP(:toStr, 'YYYY-MM-DD HH24:MI:SS')) " +
           "AND (:actorUserId IS NULL OR a.actor_user_id = :actorUserId) " +
           "AND (:actionType IS NULL OR a.action = :actionType) " +
           "AND (:resourceType IS NULL OR a.resource_type = :resourceType) " +
           "AND (:keyword IS NULL OR :keyword = '' OR " +
           "     LOWER(CAST(a.action AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(CAST(a.resource_type AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "ORDER BY a.created_at DESC",
           nativeQuery = true,
           countQuery = "SELECT COUNT(*) FROM com_audit_logs a " +
           "WHERE a.tenant_id = :tenantId " +
           "AND (:fromStr IS NULL OR :fromStr = '' OR a.created_at >= TO_TIMESTAMP(:fromStr, 'YYYY-MM-DD HH24:MI:SS')) " +
           "AND (:toStr IS NULL OR :toStr = '' OR a.created_at <= TO_TIMESTAMP(:toStr, 'YYYY-MM-DD HH24:MI:SS')) " +
           "AND (:actorUserId IS NULL OR a.actor_user_id = :actorUserId) " +
           "AND (:actionType IS NULL OR a.action = :actionType) " +
           "AND (:resourceType IS NULL OR a.resource_type = :resourceType) " +
           "AND (:keyword IS NULL OR :keyword = '' OR " +
           "     LOWER(CAST(a.action AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(CAST(a.resource_type AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<AuditLog> findByTenantIdAndFilters(
            @Param("tenantId") Long tenantId,
            @Param("fromStr") String fromStr,
            @Param("toStr") String toStr,
            @Param("actorUserId") Long actorUserId,
            @Param("actionType") String actionType,
            @Param("resourceType") String resourceType,
            @Param("keyword") String keyword,
            Pageable pageable);
}
