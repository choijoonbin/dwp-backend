package com.dwp.services.auth.repository.monitoring;

import com.dwp.services.auth.entity.monitoring.EventLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * 이벤트 로그 Repository
 */
@Repository
public interface EventLogRepository extends JpaRepository<EventLog, Long> {
    
    /**
     * 테넌트 및 기간별 이벤트 로그 조회 (페이징)
     */
    @Query("SELECT e FROM EventLog e " +
           "WHERE e.tenantId = :tenantId " +
           "AND e.occurredAt >= :from " +
           "AND e.occurredAt <= :to " +
           "ORDER BY e.occurredAt DESC")
    Page<EventLog> findByTenantIdAndOccurredAtBetween(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);
    
    /**
     * 테넌트, 기간, 이벤트 타입별 조회
     */
    @Query("SELECT e FROM EventLog e " +
           "WHERE e.tenantId = :tenantId " +
           "AND e.occurredAt >= :from " +
           "AND e.occurredAt <= :to " +
           "AND (:eventType IS NULL OR e.eventType = :eventType) " +
           "AND (:resourceKey IS NULL OR e.resourceKey = :resourceKey) " +
           "ORDER BY e.occurredAt DESC")
    Page<EventLog> findByTenantIdAndFilters(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("eventType") String eventType,
            @Param("resourceKey") String resourceKey,
            Pageable pageable);
    
    /**
     * 키워드 검색 (action, label, path)
     */
    @Query("SELECT e FROM EventLog e " +
           "WHERE e.tenantId = :tenantId " +
           "AND e.occurredAt >= :from " +
           "AND e.occurredAt <= :to " +
           "AND (:eventType IS NULL OR e.eventType = :eventType) " +
           "AND (:resourceKey IS NULL OR e.resourceKey = :resourceKey) " +
           "AND (:keyword IS NULL OR " +
           "     LOWER(e.action) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(e.label) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(e.path) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "ORDER BY e.occurredAt DESC")
    Page<EventLog> findByTenantIdAndFiltersWithKeyword(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("eventType") String eventType,
            @Param("resourceKey") String resourceKey,
            @Param("keyword") String keyword,
            Pageable pageable);
    
    /**
     * 테넌트 및 기간별 이벤트 수 집계
     */
    @Query("SELECT COUNT(e) FROM EventLog e " +
           "WHERE e.tenantId = :tenantId " +
           "AND e.occurredAt >= :from " +
           "AND e.occurredAt <= :to")
    Long countByTenantIdAndOccurredAtBetween(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
