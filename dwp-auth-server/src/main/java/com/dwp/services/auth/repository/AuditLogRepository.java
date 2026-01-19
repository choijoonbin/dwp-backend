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
}
