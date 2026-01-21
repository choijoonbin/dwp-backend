package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.CodeUsage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 코드 사용 정의 Repository
 */
@Repository
public interface CodeUsageRepository extends JpaRepository<CodeUsage, Long> {
    
    /**
     * 테넌트 ID와 리소스 키로 활성화된 코드 그룹 키 목록 조회
     */
    @Query("SELECT cu.codeGroupKey FROM CodeUsage cu " +
           "WHERE cu.tenantId = :tenantId " +
           "AND cu.resourceKey = :resourceKey " +
           "AND cu.enabled = true " +
           "ORDER BY cu.sortOrder ASC NULLS LAST")
    List<String> findEnabledCodeGroupKeysByTenantIdAndResourceKey(
            @Param("tenantId") Long tenantId,
            @Param("resourceKey") String resourceKey);
    
    /**
     * 테넌트 ID와 리소스 키로 코드 사용 정의 목록 조회
     */
    List<CodeUsage> findByTenantIdAndResourceKeyOrderBySortOrderAsc(
            Long tenantId, String resourceKey);
    
    /**
     * 테넌트 ID와 코드 그룹 키로 코드 사용 정의 목록 조회
     */
    List<CodeUsage> findByTenantIdAndCodeGroupKey(Long tenantId, String codeGroupKey);
    
    /**
     * 테넌트 ID와 ID로 조회
     */
    Optional<CodeUsage> findByTenantIdAndSysCodeUsageId(Long tenantId, Long sysCodeUsageId);
    
    /**
     * PR-07A: 키워드 검색 (리소스 키 또는 코드 그룹 키) + enabled 필터
     */
    @Query("SELECT cu FROM CodeUsage cu " +
           "WHERE cu.tenantId = :tenantId " +
           "AND (:resourceKey IS NULL OR cu.resourceKey = :resourceKey) " +
           "AND (:keyword IS NULL OR " +
           "     LOWER(cu.resourceKey) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(cu.codeGroupKey) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:enabled IS NULL OR cu.enabled = :enabled) " +
           "ORDER BY cu.resourceKey ASC, cu.sortOrder ASC NULLS LAST")
    Page<CodeUsage> findByTenantIdAndFilters(
            @Param("tenantId") Long tenantId,
            @Param("resourceKey") String resourceKey,
            @Param("keyword") String keyword,
            @Param("enabled") Boolean enabled,
            Pageable pageable);
}
