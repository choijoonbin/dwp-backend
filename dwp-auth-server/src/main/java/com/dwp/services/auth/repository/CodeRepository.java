package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.Code;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 코드 Repository
 */
@Repository
public interface CodeRepository extends JpaRepository<Code, Long> {
    
    /**
     * 그룹 키와 코드로 조회
     */
    Optional<Code> findByGroupKeyAndCode(String groupKey, String code);
    
    /**
     * 그룹 키로 활성화된 코드 목록 조회 (정렬 순서 기준)
     */
    List<Code> findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(String groupKey);
    
    /**
     * 그룹 키로 코드 목록 조회 (정렬 순서 기준)
     */
    List<Code> findByGroupKeyOrderBySortOrderAsc(String groupKey);
    
    /**
     * 그룹 키 목록으로 활성화된 코드 목록 조회
     */
    List<Code> findByGroupKeyInAndIsActiveTrueOrderByGroupKeyAscSortOrderAsc(List<String> groupKeys);
    
    /**
     * 코드 존재 여부 확인
     */
    boolean existsByGroupKeyAndCodeAndIsActiveTrue(String groupKey, String code);
    
    /**
     * 테넌트 ID를 고려한 코드 조회 (BE P1-5)
     * - tenant_id가 null인 전사 공통 코드 우선
     * - tenant_id가 있는 테넌트별 커스텀 코드는 해당 테넌트에서만 조회
     */
    @Query("SELECT c FROM Code c " +
           "WHERE c.groupKey = :groupKey " +
           "AND c.isActive = true " +
           "AND (c.tenantId IS NULL OR c.tenantId = :tenantId) " +
           "ORDER BY CASE WHEN c.tenantId IS NULL THEN 0 ELSE 1 END, c.sortOrder ASC")
    List<Code> findByGroupKeyAndTenantIdOrderBySortOrderAsc(@Param("groupKey") String groupKey, @Param("tenantId") Long tenantId);
}
