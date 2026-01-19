package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 역할-권한 매핑 Repository
 */
@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {
    
    /**
     * 역할 ID 목록으로 권한 매핑 조회
     */
    @Query("SELECT rp FROM RolePermission rp " +
           "WHERE rp.tenantId = :tenantId " +
           "AND rp.roleId IN :roleIds " +
           "AND rp.effect = 'ALLOW'")
    List<RolePermission> findByTenantIdAndRoleIdIn(
        @Param("tenantId") Long tenantId,
        @Param("roleIds") List<Long> roleIds
    );
    
    /**
     * 역할 ID로 권한 매핑 조회
     */
    List<RolePermission> findByTenantIdAndRoleId(Long tenantId, Long roleId);
    
    /**
     * 역할 ID로 권한 매핑 삭제
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM RolePermission rp " +
           "WHERE rp.tenantId = :tenantId " +
           "AND rp.roleId = :roleId")
    void deleteByTenantIdAndRoleId(@Param("tenantId") Long tenantId, @Param("roleId") Long roleId);
}
