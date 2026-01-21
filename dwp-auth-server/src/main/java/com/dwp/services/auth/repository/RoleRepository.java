package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 역할 Repository
 */
@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {
    
    List<Role> findByRoleIdIn(List<Long> roleIds);
    
    /**
     * 테넌트 ID와 역할 ID로 조회
     */
    Optional<Role> findByTenantIdAndRoleId(Long tenantId, Long roleId);
    
    /**
     * 테넌트 ID와 역할 코드로 조회
     */
    Optional<Role> findByTenantIdAndCode(Long tenantId, String code);
    
    /**
     * 테넌트 ID로 모든 역할 조회
     */
    List<Role> findByTenantIdOrderByNameAsc(Long tenantId);
    
    /**
     * 키워드 검색 (역할명 또는 코드)
     * 
     * Note: V20 마이그레이션 이후 bytea 타입이 VARCHAR로 변환되었지만,
     * Hibernate가 여전히 bytea로 인식할 수 있어 CAST를 사용하여 명시적으로 VARCHAR로 변환
     */
    @Query(value = "SELECT r.* FROM com_roles r " +
           "WHERE r.tenant_id = :tenantId " +
           "AND (:keyword IS NULL OR " +
           "     LOWER(CAST(r.name AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(CAST(r.code AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:status IS NULL OR r.status = :status) " +
           "ORDER BY CAST(r.name AS VARCHAR) ASC",
           nativeQuery = true,
           countQuery = "SELECT COUNT(*) FROM com_roles r " +
           "WHERE r.tenant_id = :tenantId " +
           "AND (:keyword IS NULL OR " +
           "     LOWER(CAST(r.name AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(CAST(r.code AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:status IS NULL OR r.status = :status)")
    Page<Role> findByTenantIdAndKeyword(
            @Param("tenantId") Long tenantId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            Pageable pageable);
}
