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
     * 키워드 검색 (이름, 이메일, 로그인 ID) - 보강 (BE P1-5 Enhanced)
     * idpProviderType 필터는 UserAccount와 JOIN하여 처리
     * 
     * Note: V20 마이그레이션 이후 bytea 타입이 VARCHAR로 변환되었지만,
     * Hibernate가 여전히 bytea로 인식할 수 있어 CAST를 사용하여 명시적으로 VARCHAR로 변환
     */
    @Query(value = "SELECT DISTINCT u.user_id, u.tenant_id, u.display_name, u.email, u.primary_department_id, u.status, " +
           "u.mfa_enabled, u.created_at, u.created_by, u.updated_at, u.updated_by " +
           "FROM com_users u " +
           "LEFT JOIN com_user_accounts ua ON ua.user_id = u.user_id AND ua.tenant_id = u.tenant_id " +
           "WHERE u.tenant_id = :tenantId " +
           "AND (:keyword IS NULL OR " +
           "     LOWER(u.display_name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     (ua.principal IS NOT NULL AND LOWER(CAST(ua.principal AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%')))) " +
           "AND (:departmentId IS NULL OR u.primary_department_id = :departmentId) " +
           "AND (:status IS NULL OR u.status = :status) " +
           "AND (:idpProviderType IS NULL OR ua.provider_type = :idpProviderType) " +
           "ORDER BY u.created_at DESC",
           nativeQuery = true,
           countQuery = "SELECT COUNT(DISTINCT u.user_id) FROM com_users u " +
           "LEFT JOIN com_user_accounts ua ON ua.user_id = u.user_id AND ua.tenant_id = u.tenant_id " +
           "WHERE u.tenant_id = :tenantId " +
           "AND (:keyword IS NULL OR " +
           "     LOWER(u.display_name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     (ua.principal IS NOT NULL AND LOWER(CAST(ua.principal AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%')))) " +
           "AND (:departmentId IS NULL OR u.primary_department_id = :departmentId) " +
           "AND (:status IS NULL OR u.status = :status) " +
           "AND (:idpProviderType IS NULL OR ua.provider_type = :idpProviderType)")
    Page<User> findByTenantIdAndFilters(
            @Param("tenantId") Long tenantId,
            @Param("keyword") String keyword,
            @Param("departmentId") Long departmentId,
            @Param("status") String status,
            @Param("idpProviderType") String idpProviderType,
            Pageable pageable);

    /**
     * 키워드 검색 + 사용자 ID 범위 제한 (앱 스코프: 해당 앱 역할을 가진 사용자만)
     * appCode/roleIds 필터 시 먼저 scopeUserIds를 구한 뒤 이 메서드로 검색·페이징.
     */
    @Query(value = "SELECT DISTINCT u.user_id, u.tenant_id, u.display_name, u.email, u.primary_department_id, u.status, " +
           "u.mfa_enabled, u.created_at, u.created_by, u.updated_at, u.updated_by " +
           "FROM com_users u " +
           "LEFT JOIN com_user_accounts ua ON ua.user_id = u.user_id AND ua.tenant_id = u.tenant_id " +
           "WHERE u.tenant_id = :tenantId " +
           "AND u.user_id IN (:userIds) " +
           "AND (:keyword IS NULL OR " +
           "     LOWER(u.display_name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     (ua.principal IS NOT NULL AND LOWER(CAST(ua.principal AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%')))) " +
           "AND (:departmentId IS NULL OR u.primary_department_id = :departmentId) " +
           "AND (:status IS NULL OR u.status = :status) " +
           "AND (:idpProviderType IS NULL OR ua.provider_type = :idpProviderType) " +
           "ORDER BY u.created_at DESC",
           nativeQuery = true,
           countQuery = "SELECT COUNT(DISTINCT u.user_id) FROM com_users u " +
           "LEFT JOIN com_user_accounts ua ON ua.user_id = u.user_id AND ua.tenant_id = u.tenant_id " +
           "WHERE u.tenant_id = :tenantId " +
           "AND u.user_id IN (:userIds) " +
           "AND (:keyword IS NULL OR " +
           "     LOWER(u.display_name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     (ua.principal IS NOT NULL AND LOWER(CAST(ua.principal AS VARCHAR)) LIKE LOWER(CONCAT('%', :keyword, '%')))) " +
           "AND (:departmentId IS NULL OR u.primary_department_id = :departmentId) " +
           "AND (:status IS NULL OR u.status = :status) " +
           "AND (:idpProviderType IS NULL OR ua.provider_type = :idpProviderType)")
    Page<User> findByTenantIdAndFiltersAndUserIdIn(
            @Param("tenantId") Long tenantId,
            @Param("keyword") String keyword,
            @Param("departmentId") Long departmentId,
            @Param("status") String status,
            @Param("idpProviderType") String idpProviderType,
            @Param("userIds") List<Long> userIds,
            Pageable pageable);
    
    /**
     * 테넌트 내 사용자 ID 목록으로 조회 (display_name 배치 조회용)
     */
    List<User> findByTenantIdAndUserIdIn(Long tenantId, List<Long> userIds);

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
    
    /**
     * PR-03E: 부서 ID로 사용자 목록 조회 (캐시 무효화용)
     */
    @Query("SELECT u FROM User u " +
           "WHERE u.tenantId = :tenantId " +
           "AND u.primaryDepartmentId = :departmentId")
    List<User> findByTenantIdAndPrimaryDepartmentId(
            @Param("tenantId") Long tenantId,
            @Param("departmentId") Long departmentId);
}
