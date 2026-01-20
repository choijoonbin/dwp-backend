package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 사용자 Repository
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    /**
     * 테넌트 ID와 이메일로 사용자 조회
     */
    Optional<User> findByTenantIdAndEmail(Long tenantId, String email);
    
    /**
     * 사용자 ID와 테넌트 ID로 조회 (보안 검증용)
     */
    Optional<User> findByUserIdAndTenantId(Long userId, Long tenantId);
    
    /**
     * 테넌트 ID와 사용자 ID로 조회
     */
    Optional<User> findByTenantIdAndUserId(Long tenantId, Long userId);
    
    /**
     * 키워드 검색 (이름, 이메일) - 보강 (BE P1-5 Enhanced)
     * idpProviderType 필터는 UserAccount와 JOIN하여 처리
     */
    @Query("SELECT DISTINCT u FROM User u " +
           "LEFT JOIN UserAccount ua ON ua.userId = u.userId " +
           "WHERE u.tenantId = :tenantId " +
           "AND (:keyword IS NULL OR " +
           "     LOWER(u.displayName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(ua.principal) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:departmentId IS NULL OR u.primaryDepartmentId = :departmentId) " +
           "AND (:status IS NULL OR u.status = :status) " +
           "AND (:idpProviderType IS NULL OR ua.providerType = :idpProviderType) " +
           "ORDER BY u.displayName ASC")
    Page<User> findByTenantIdAndFilters(
            @Param("tenantId") Long tenantId,
            @Param("keyword") String keyword,
            @Param("departmentId") Long departmentId,
            @Param("status") String status,
            @Param("idpProviderType") String idpProviderType,
            Pageable pageable);
    
    /**
     * 역할 ID로 사용자 목록 조회
     */
    @Query("SELECT DISTINCT u FROM User u " +
           "JOIN RoleMember rm ON rm.subjectId = u.userId " +
           "WHERE u.tenantId = :tenantId " +
           "AND rm.tenantId = :tenantId " +
           "AND rm.roleId = :roleId " +
           "AND rm.subjectType = 'USER' " +
           "ORDER BY u.displayName ASC")
    List<User> findByTenantIdAndRoleId(
            @Param("tenantId") Long tenantId,
            @Param("roleId") Long roleId);
}
