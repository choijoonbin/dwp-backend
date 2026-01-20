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
    
    /**
     * API 호출 이력 필터링 조회 (날짜 범위 없음)
     */
    @Query("SELECT a FROM ApiCallHistory a " +
           "WHERE a.tenantId = :tenantId " +
           "AND (:keyword IS NULL OR :keyword = '' OR " +
           "     LOWER(a.path) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(a.method) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:apiName IS NULL OR :apiName = '' OR LOWER(a.path) LIKE LOWER(CONCAT('%', :apiName, '%'))) " +
           "AND (:apiUrl IS NULL OR :apiUrl = '' OR LOWER(a.path) LIKE LOWER(CONCAT('%', :apiUrl, '%'))) " +
           "AND (:statusCode IS NULL OR a.statusCode = :statusCode) " +
           "AND (:userId IS NULL OR a.userId = :userId) " +
           "ORDER BY a.createdAt DESC")
    Page<ApiCallHistory> findByTenantIdAndFiltersWithoutDate(
            @Param("tenantId") Long tenantId,
            @Param("keyword") String keyword,
            @Param("apiName") String apiName,
            @Param("apiUrl") String apiUrl,
            @Param("statusCode") Integer statusCode,
            @Param("userId") Long userId,
            Pageable pageable);
    
    /**
     * API 호출 이력 필터링 조회 (날짜 범위 포함)
     */
    @Query("SELECT a FROM ApiCallHistory a " +
           "WHERE a.tenantId = :tenantId " +
           "AND a.createdAt >= :from " +
           "AND a.createdAt <= :to " +
           "AND (:keyword IS NULL OR :keyword = '' OR " +
           "     LOWER(a.path) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(a.method) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:apiName IS NULL OR :apiName = '' OR LOWER(a.path) LIKE LOWER(CONCAT('%', :apiName, '%'))) " +
           "AND (:apiUrl IS NULL OR :apiUrl = '' OR LOWER(a.path) LIKE LOWER(CONCAT('%', :apiUrl, '%'))) " +
           "AND (:statusCode IS NULL OR a.statusCode = :statusCode) " +
           "AND (:userId IS NULL OR a.userId = :userId) " +
           "ORDER BY a.createdAt DESC")
    Page<ApiCallHistory> findByTenantIdAndFiltersWithDate(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("keyword") String keyword,
            @Param("apiName") String apiName,
            @Param("apiUrl") String apiUrl,
            @Param("statusCode") Integer statusCode,
            @Param("userId") Long userId,
            Pageable pageable);
}
