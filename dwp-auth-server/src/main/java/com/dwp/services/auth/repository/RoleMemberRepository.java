package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.RoleMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface RoleMemberRepository extends JpaRepository<RoleMember, Long> {

    interface EffectiveRoleMembership {
        Long getUserId();

        Long getRoleId();
    }

    @Query(value = """
            SELECT member.role_id
            FROM com_role_members member
            WHERE member.tenant_id = :tenantId
              AND member.user_id = :userId
            UNION
            SELECT assignment.role_id
            FROM com_group_role_assignments assignment
            JOIN com_group_members membership
              ON membership.tenant_id = assignment.tenant_id
             AND membership.group_id = assignment.group_id
            JOIN com_groups access_group
              ON access_group.tenant_id = membership.tenant_id
             AND access_group.group_id = membership.group_id
            WHERE assignment.tenant_id = :tenantId
              AND membership.user_id = :userId
              AND access_group.status = 'ACTIVE'
              AND assignment.lifecycle_state = 'ACTIVE'
              AND assignment.assignment_type = 'ACTIVE'
              AND assignment.scope_type = 'TENANT'
              AND (assignment.valid_from IS NULL OR assignment.valid_from <= CURRENT_TIMESTAMP)
              AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
            UNION
            SELECT active_grant.role_id
            FROM com_active_privileged_grants active_grant
            WHERE active_grant.tenant_id = :tenantId
              AND active_grant.user_id = :userId
              AND active_grant.scope_type = 'TENANT'
              AND active_grant.revoked_at IS NULL
              AND active_grant.activated_at <= CURRENT_TIMESTAMP
              AND active_grant.expires_at > CURRENT_TIMESTAMP
            """, nativeQuery = true)
    List<Long> findRoleIds(
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId);

    @Query(value = """
            SELECT member.user_id AS userId, member.role_id AS roleId
            FROM com_role_members member
            WHERE member.tenant_id = :tenantId
              AND member.user_id IN (:userIds)
            UNION
            SELECT membership.user_id AS userId, assignment.role_id AS roleId
            FROM com_group_role_assignments assignment
            JOIN com_group_members membership
              ON membership.tenant_id = assignment.tenant_id
             AND membership.group_id = assignment.group_id
            JOIN com_groups access_group
              ON access_group.tenant_id = membership.tenant_id
             AND access_group.group_id = membership.group_id
            WHERE assignment.tenant_id = :tenantId
              AND membership.user_id IN (:userIds)
              AND access_group.status = 'ACTIVE'
              AND assignment.lifecycle_state = 'ACTIVE'
              AND assignment.assignment_type = 'ACTIVE'
              AND (assignment.valid_from IS NULL OR assignment.valid_from <= CURRENT_TIMESTAMP)
              AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
            UNION
            SELECT active_grant.user_id AS userId, active_grant.role_id AS roleId
            FROM com_active_privileged_grants active_grant
            WHERE active_grant.tenant_id = :tenantId
              AND active_grant.user_id IN (:userIds)
              AND active_grant.scope_type = 'TENANT'
              AND active_grant.revoked_at IS NULL
              AND active_grant.activated_at <= CURRENT_TIMESTAMP
              AND active_grant.expires_at > CURRENT_TIMESTAMP
            """, nativeQuery = true)
    List<EffectiveRoleMembership> findEffectiveRoleMemberships(
            @Param("tenantId") Long tenantId,
            @Param("userIds") Collection<Long> userIds);

    List<RoleMember> findByTenantIdAndUserId(Long tenantId, Long userId);

    List<RoleMember> findByTenantIdAndRoleId(Long tenantId, Long roleId);

    List<RoleMember> findByTenantIdAndUserIdIn(Long tenantId, Collection<Long> userIds);

    long countByTenantIdAndRoleId(Long tenantId, Long roleId);
}
