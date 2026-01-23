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
     * 
     * Note: Hibernate가 resourceKey, codeGroupKey를 bytea로 인식하는 문제로 Native Query 사용
     * CAST를 사용하여 명시적으로 VARCHAR로 변환
     */
    /**
     * P1-2: codeGroupKey 필터 추가
     */
    @Query(value = "SELECT cu.sys_code_usage_id, cu.tenant_id, cu.resource_key, cu.code_group_key, " +
           "cu.scope, cu.enabled, cu.sort_order, cu.remark, " +
           "cu.created_at, cu.created_by, cu.updated_at, cu.updated_by " +
           "FROM sys_code_usages cu " +
           "WHERE cu.tenant_id = :tenantId " +
           "AND (:resourceKey IS NULL OR cu.resource_key = :resourceKey) " +
           "AND (:codeGroupKey IS NULL OR :codeGroupKey = '' OR cu.code_group_key = :codeGroupKey) " +
           "AND (:keyword IS NULL OR :keyword = '' OR " +
           "     LOWER(CAST(cu.resource_key AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(CAST(cu.code_group_key AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:enabled IS NULL OR cu.enabled = :enabled) " +
           "ORDER BY CAST(cu.resource_key AS VARCHAR) ASC, cu.sort_order ASC NULLS LAST",
           nativeQuery = true,
           countQuery = "SELECT COUNT(*) FROM sys_code_usages cu " +
           "WHERE cu.tenant_id = :tenantId " +
           "AND (:resourceKey IS NULL OR cu.resource_key = :resourceKey) " +
           "AND (:codeGroupKey IS NULL OR :codeGroupKey = '' OR cu.code_group_key = :codeGroupKey) " +
           "AND (:keyword IS NULL OR :keyword = '' OR " +
           "     LOWER(CAST(cu.resource_key AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(CAST(cu.code_group_key AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:enabled IS NULL OR cu.enabled = :enabled)")
    Page<CodeUsage> findByTenantIdAndFilters(
            @Param("tenantId") Long tenantId,
            @Param("resourceKey") String resourceKey,
            @Param("codeGroupKey") String codeGroupKey,
            @Param("keyword") String keyword,
            @Param("enabled") Boolean enabled,
            Pageable pageable);
}
