package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.PageViewEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface PageViewEventRepository extends JpaRepository<PageViewEvent, Long> {

    Page<PageViewEvent> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);

    @Query("SELECT COUNT(p) FROM PageViewEvent p WHERE p.tenantId = :tenantId AND p.eventType = 'PAGE_VIEW' AND p.createdAt BETWEEN :from AND :to")
    long countPvByTenantIdAndCreatedAtBetween(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(DISTINCT p.sessionId) FROM PageViewEvent p WHERE p.tenantId = :tenantId AND p.createdAt BETWEEN :from AND :to AND p.sessionId IS NOT NULL")
    long countUvByTenantIdAndCreatedAtBetween(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(p) FROM PageViewEvent p WHERE p.tenantId = :tenantId AND p.eventType != 'PAGE_VIEW' AND p.createdAt BETWEEN :from AND :to")
    long countEventsByTenantIdAndCreatedAtBetween(@Param("tenantId") Long tenantId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
    
    /**
     * 방문자별 집계 (DB 레벨 페이징). sessionId가 null이면 제외.
     */
    @Query(value = "SELECT p.sessionId, MIN(p.createdAt) as firstSeen, MAX(p.createdAt) as lastSeen, " +
           "COUNT(p) as pvCount, MAX(p.pageKey) as lastPath " +
           "FROM PageViewEvent p " +
           "WHERE p.tenantId = :tenantId AND p.createdAt BETWEEN :from AND :to AND p.sessionId IS NOT NULL " +
           "GROUP BY p.sessionId ORDER BY MAX(p.createdAt) DESC",
           countQuery = "SELECT COUNT(DISTINCT p.sessionId) FROM PageViewEvent p " +
           "WHERE p.tenantId = :tenantId AND p.createdAt BETWEEN :from AND :to AND p.sessionId IS NOT NULL")
    Page<Object[]> findVisitorSummariesByTenantIdAndCreatedAtBetween(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    /**
     * 방문자별 집계 + 키워드 검색 (DB 레벨 페이징).
     */
    @Query(value = "SELECT p.sessionId, MIN(p.createdAt) as firstSeen, MAX(p.createdAt) as lastSeen, " +
           "COUNT(p) as pvCount, MAX(p.pageKey) as lastPath " +
           "FROM PageViewEvent p " +
           "WHERE p.tenantId = :tenantId AND p.createdAt BETWEEN :from AND :to AND p.sessionId IS NOT NULL " +
           "AND (LOWER(p.sessionId) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(p.pageKey) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "GROUP BY p.sessionId ORDER BY MAX(p.createdAt) DESC",
           countQuery = "SELECT COUNT(DISTINCT p.sessionId) FROM PageViewEvent p " +
           "WHERE p.tenantId = :tenantId AND p.createdAt BETWEEN :from AND :to AND p.sessionId IS NOT NULL " +
           "AND (LOWER(p.sessionId) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(p.pageKey) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Object[]> findVisitorSummariesByTenantIdAndKeyword(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("keyword") String keyword,
            Pageable pageable);
    
    /**
     * 페이지뷰 필터링 조회 (날짜 범위 없음)
     */
    @Query("SELECT p FROM PageViewEvent p " +
           "WHERE p.tenantId = :tenantId " +
           "AND (:keyword IS NULL OR :keyword = '' OR " +
           "     LOWER(p.pageKey) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(p.eventName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(p.sessionId) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:route IS NULL OR :route = '' OR LOWER(p.pageKey) LIKE LOWER(CONCAT('%', :route, '%'))) " +
           "AND (:menu IS NULL OR :menu = '' OR LOWER(p.eventName) LIKE LOWER(CONCAT('%', :menu, '%'))) " +
           "AND (:path IS NULL OR :path = '' OR LOWER(p.pageKey) LIKE LOWER(CONCAT('%', :path, '%'))) " +
           "AND (:userId IS NULL OR p.userId = :userId) " +
           "ORDER BY p.createdAt DESC")
    Page<PageViewEvent> findByTenantIdAndFiltersWithoutDate(
            @Param("tenantId") Long tenantId,
            @Param("keyword") String keyword,
            @Param("route") String route,
            @Param("menu") String menu,
            @Param("path") String path,
            @Param("userId") Long userId,
            Pageable pageable);
    
    /**
     * 페이지뷰 필터링 조회 (날짜 범위 포함)
     */
    @Query("SELECT p FROM PageViewEvent p " +
           "WHERE p.tenantId = :tenantId " +
           "AND p.createdAt >= :from " +
           "AND p.createdAt <= :to " +
           "AND (:keyword IS NULL OR :keyword = '' OR " +
           "     LOWER(p.pageKey) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(p.eventName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(p.sessionId) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:route IS NULL OR :route = '' OR LOWER(p.pageKey) LIKE LOWER(CONCAT('%', :route, '%'))) " +
           "AND (:menu IS NULL OR :menu = '' OR LOWER(p.eventName) LIKE LOWER(CONCAT('%', :menu, '%'))) " +
           "AND (:path IS NULL OR :path = '' OR LOWER(p.pageKey) LIKE LOWER(CONCAT('%', :path, '%'))) " +
           "AND (:userId IS NULL OR p.userId = :userId) " +
           "ORDER BY p.createdAt DESC")
    Page<PageViewEvent> findByTenantIdAndFiltersWithDate(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("keyword") String keyword,
            @Param("route") String route,
            @Param("menu") String menu,
            @Param("path") String path,
            @Param("userId") Long userId,
            Pageable pageable);
}
