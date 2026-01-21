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
     * Note: metadataJson에 대한 keyword 검색은 제외 (bytea 타입 인식 문제)
     * metadataJson 검색이 필요한 경우 별도 API로 분리 권장
     */
    @Query("SELECT a FROM AuditLog a " +
           "WHERE a.tenantId = :tenantId " +
           "AND (:from IS NULL OR a.createdAt >= :from) " +
           "AND (:to IS NULL OR a.createdAt <= :to) " +
           "AND (:actorUserId IS NULL OR a.actorUserId = :actorUserId) " +
           "AND (:actionType IS NULL OR a.action = :actionType) " +
           "AND (:resourceType IS NULL OR a.resourceType = :resourceType) " +
           "AND (:keyword IS NULL OR " +
           "     LOWER(a.action) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(a.resourceType) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "ORDER BY a.createdAt DESC")
    Page<AuditLog> findByTenantIdAndFilters(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("actorUserId") Long actorUserId,
            @Param("actionType") String actionType,
            @Param("resourceType") String resourceType,
            @Param("keyword") String keyword,
            Pageable pageable);
}
