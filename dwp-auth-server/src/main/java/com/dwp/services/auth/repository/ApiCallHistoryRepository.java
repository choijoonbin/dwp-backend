package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.ApiCallHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface ApiCallHistoryRepository extends JpaRepository<ApiCallHistory, Long> {
    
    Page<ApiCallHistory> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);

    @Query("SELECT COUNT(a) FROM ApiCallHistory a WHERE a.tenantId = :tenantId AND a.createdAt BETWEEN :from AND :to")
    long countByTenantIdAndCreatedAtBetween(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(a) FROM ApiCallHistory a WHERE a.tenantId = :tenantId AND a.statusCode >= 400 AND a.createdAt BETWEEN :from AND :to")
    long countErrorsByTenantIdAndCreatedAtBetween(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
