package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.CodeGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 코드 그룹 Repository
 */
@Repository
public interface CodeGroupRepository extends JpaRepository<CodeGroup, Long> {
    
    /**
     * 그룹 키로 조회
     */
    Optional<CodeGroup> findByGroupKey(String groupKey);
    
    /**
     * 활성화된 그룹 목록 조회
     */
    List<CodeGroup> findByIsActiveTrueOrderByGroupKey();
    
    /**
     * P1-1: keyword, tenantScope, enabled 필터 지원
     * tenantScope: COMMON(코드에 tenant_id IS NULL 존재), TENANT(tenant_id IS NOT NULL 존재), ALL/빈값=필터없음
     */
    @Query("SELECT g FROM CodeGroup g WHERE " +
            "(:keyword IS NULL OR :keyword = '' OR LOWER(g.groupKey) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(g.groupName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR (g.description IS NOT NULL AND LOWER(g.description) LIKE LOWER(CONCAT('%', :keyword, '%')))) " +
            "AND (:enabled IS NULL OR g.isActive = :enabled) " +
            "AND (:tenantScope IS NULL OR :tenantScope = '' OR :tenantScope = 'ALL' OR " +
            "  ((:tenantScope = 'COMMON') AND (EXISTS (SELECT 1 FROM Code c WHERE c.groupKey = g.groupKey AND c.tenantId IS NULL))) OR " +
            "  ((:tenantScope = 'TENANT') AND (EXISTS (SELECT 1 FROM Code c WHERE c.groupKey = g.groupKey AND c.tenantId IS NOT NULL)))) " +
            "ORDER BY g.groupKey")
    List<CodeGroup> findWithFilters(@Param("keyword") String keyword, @Param("tenantScope") String tenantScope, @Param("enabled") Boolean enabled);
}
