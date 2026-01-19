package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.Department;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 부서 Repository
 */
@Repository
public interface DepartmentRepository extends JpaRepository<Department, Long> {
    
    /**
     * 테넌트 ID와 부서 ID로 조회
     */
    Optional<Department> findByTenantIdAndDepartmentId(Long tenantId, Long departmentId);
    
    /**
     * 테넌트 ID와 부서 코드로 조회
     */
    Optional<Department> findByTenantIdAndCode(Long tenantId, String code);
    
    /**
     * 테넌트 ID로 모든 부서 조회
     */
    List<Department> findByTenantIdOrderByNameAsc(Long tenantId);
    
    /**
     * 테넌트 ID와 상태로 부서 조회
     */
    List<Department> findByTenantIdAndStatusOrderByNameAsc(Long tenantId, String status);
    
    /**
     * 키워드 검색 (부서명 또는 코드)
     */
    @Query("SELECT d FROM Department d " +
           "WHERE d.tenantId = :tenantId " +
           "AND (:keyword IS NULL OR " +
           "     LOWER(d.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(d.code) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "ORDER BY d.name ASC")
    Page<Department> findByTenantIdAndKeyword(
            @Param("tenantId") Long tenantId,
            @Param("keyword") String keyword,
            Pageable pageable);
}
