package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 리소스 Repository
 */
@Repository
public interface ResourceRepository extends JpaRepository<Resource, Long> {
    
    /**
     * 리소스 ID 목록으로 조회
     */
    List<Resource> findByResourceIdIn(List<Long> resourceIds);
    
    /**
     * 테넌트 ID와 리소스 ID로 조회
     */
    Optional<Resource> findByTenantIdAndResourceId(Long tenantId, Long resourceId);
    
    /**
     * 테넌트 ID, 타입, 키로 조회
     */
    Optional<Resource> findByTenantIdAndTypeAndKey(Long tenantId, String type, String key);
    
    /**
     * 테넌트 ID와 키로 조회 (타입 무관, BE P1-5)
     */
    @Query("SELECT r FROM Resource r " +
           "WHERE (r.tenantId = :tenantId OR r.tenantId IS NULL) " +
           "AND r.key = :key " +
           "ORDER BY CASE WHEN r.tenantId = :tenantId THEN 0 ELSE 1 END")
    List<Resource> findByTenantIdAndKey(@Param("tenantId") Long tenantId, @Param("key") String key);
    
    /**
     * 테넌트 ID로 모든 리소스 조회 (트리 구조)
     */
    List<Resource> findByTenantIdOrderByParentResourceIdAscKeyAsc(Long tenantId);
    
    /**
     * 키워드 검색 (리소스명 또는 키) - 보강 (BE P1-5 Enhanced)
     */
    @Query("SELECT r FROM Resource r " +
           "WHERE r.tenantId = :tenantId " +
           "AND (:keyword IS NULL OR " +
           "     LOWER(r.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(r.key) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:type IS NULL OR r.type = :type) " +
           "AND (:category IS NULL OR r.resourceCategory = :category) " +
           "AND (:kind IS NULL OR r.resourceKind = :kind) " +
           "AND (:parentId IS NULL OR r.parentResourceId = :parentId) " +
           "AND (:enabled IS NULL OR r.enabled = :enabled) " +
           "ORDER BY r.name ASC")
    Page<Resource> findByTenantIdAndFilters(
            @Param("tenantId") Long tenantId,
            @Param("keyword") String keyword,
            @Param("type") String type,
            @Param("category") String category,
            @Param("kind") String kind,
            @Param("parentId") Long parentId,
            @Param("enabled") Boolean enabled,
            Pageable pageable);
}
