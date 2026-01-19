package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.RoleMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 역할 할당 Repository
 */
@Repository
public interface RoleMemberRepository extends JpaRepository<RoleMember, Long> {
    
    /**
     * 사용자에게 할당된 역할 ID 목록 조회
     */
    @Query("SELECT rm.roleId FROM RoleMember rm " +
           "WHERE rm.tenantId = :tenantId " +
           "AND rm.subjectType = 'USER' " +
           "AND rm.subjectId = :userId")
    List<Long> findRoleIdsByTenantIdAndUserId(
        @Param("tenantId") Long tenantId,
        @Param("userId") Long userId
    );
    
    /**
     * 역할 ID로 멤버 목록 조회
     */
    List<RoleMember> findByTenantIdAndRoleId(Long tenantId, Long roleId);
    
    /**
     * 사용자 ID로 역할 멤버 조회
     */
    @Query("SELECT rm FROM RoleMember rm " +
           "WHERE rm.tenantId = :tenantId " +
           "AND rm.subjectType = 'USER' " +
           "AND rm.subjectId = :userId")
    List<RoleMember> findByTenantIdAndUserId(
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId);
    
    /**
     * 역할 ID로 멤버 삭제
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM RoleMember rm " +
           "WHERE rm.tenantId = :tenantId " +
           "AND rm.roleId = :roleId")
    void deleteByTenantIdAndRoleId(@Param("tenantId") Long tenantId, @Param("roleId") Long roleId);
    
    /**
     * 사용자 ID로 역할 멤버 삭제
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM RoleMember rm " +
           "WHERE rm.tenantId = :tenantId " +
           "AND rm.subjectId = :userId " +
           "AND rm.subjectType = 'USER'")
    void deleteByTenantIdAndUserId(@Param("tenantId") Long tenantId, @Param("userId") Long userId);
}
