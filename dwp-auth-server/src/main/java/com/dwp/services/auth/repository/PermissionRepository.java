package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 권한 Repository
 */
@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {
    
    /**
     * 권한 ID 목록으로 조회
     */
    List<Permission> findByPermissionIdIn(List<Long> permissionIds);
    
    /**
     * 권한 코드로 조회
     */
    Optional<Permission> findByCode(String code);
}
