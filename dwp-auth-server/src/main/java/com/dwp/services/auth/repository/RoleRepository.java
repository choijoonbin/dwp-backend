package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    List<Role> findByRoleIdIn(Collection<Long> roleIds);

    List<Role> findByTenantIdAndStatusOrderByCodeAsc(Long tenantId, String status);

    List<Role> findByTenantIdAndCodeIn(Long tenantId, Collection<String> codes);

    Optional<Role> findByTenantIdAndCode(Long tenantId, String code);

    Optional<Role> findByRoleIdAndTenantId(Long roleId, Long tenantId);

    List<Role> findByTenantIdOrderByCodeAsc(Long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select role
            from Role role
            where role.tenantId = :tenantId and role.code = :code
            """)
    Optional<Role> findByTenantIdAndCodeForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("code") String code);
}
