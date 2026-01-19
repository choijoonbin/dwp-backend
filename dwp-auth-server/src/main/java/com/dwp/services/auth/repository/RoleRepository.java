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
     */
    @Query("SELECT r FROM Role r " +
           "WHERE r.tenantId = :tenantId " +
           "AND (:keyword IS NULL OR " +
           "     LOWER(r.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(r.code) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "ORDER BY r.name ASC")
    Page<Role> findByTenantIdAndKeyword(
            @Param("tenantId") Long tenantId,
            @Param("keyword") String keyword,
            Pageable pageable);
}
