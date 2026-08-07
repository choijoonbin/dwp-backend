package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {

    List<RolePermission> findByTenantIdAndRoleIdInAndEffect(
            Long tenantId,
            Collection<Long> roleIds,
            String effect);
}
