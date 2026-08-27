package com.dwp.services.auth.identity;

import com.dwp.services.auth.dto.PermissionDTO;
import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.entity.RolePermission;
import com.dwp.services.auth.repository.PermissionRepository;
import com.dwp.services.auth.repository.PrincipalResourceGrantRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RolePermissionRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.service.ScopedAdminDutyEvidenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EffectivePermissionKeyResolverTest {

    @Mock private RoleMemberRepository roleMembers;
    @Mock private RoleRepository roles;
    @Mock private RolePermissionRepository rolePermissions;
    @Mock private ResourceRepository resources;
    @Mock private PermissionRepository permissions;
    @Mock private PrincipalResourceGrantRepository principalGrants;
    @Mock private ScopedAdminDutyEvidenceService scopedDuties;

    @Test
    void denyFromAnyEffectiveSourceOverridesAllAllowsForTheExactKey() {
        when(roleMembers.findRoleIds(3L, 17L)).thenReturn(List.of(10L, 11L));
        when(roles.findByRoleIdIn(List.of(10L, 11L))).thenReturn(List.of(
                role(10L, 3L, "ACTIVE"),
                role(11L, 3L, "ACTIVE")));
        when(rolePermissions.findByTenantIdAndRoleIdIn(3L, List.of(10L, 11L)))
                .thenReturn(List.of(
                        assignment(10L, 101L, 201L, "ALLOW"),
                        assignment(11L, 102L, 201L, "ALLOW")));
        when(resources.findAllById(List.of(101L, 102L))).thenReturn(List.of(
                resource(101L, null, "APP.WORK", true),
                resource(102L, 3L, "APP.APPS", true)));
        when(permissions.findAllById(List.of(201L)))
                .thenReturn(List.of(permission(201L, "VIEW")));
        when(principalGrants.findEffective(3L, 17L)).thenReturn(List.of(
                new PrincipalResourceGrantRepository.EffectiveGrant(
                        "grant-1", "APP", "APP.WORK", "Work", "VIEW", "View", "DENY")));
        when(scopedDuties.capabilityPermissions(3L, 17L)).thenReturn(List.of(
                permissionDto("APP.WORK", "VIEW", "ALLOW"),
                permissionDto("APP.ACTIVITY", "VIEW", "ALLOW")));

        assertThat(resolver().resolve(3L, 17L))
                .containsExactly("APP.ACTIVITY:VIEW", "APP.APPS:VIEW")
                .doesNotContain("APP.WORK:VIEW");
    }

    @Test
    void ignoresDisabledCrossTenantAndMalformedPermissionEvidence() {
        when(roleMembers.findRoleIds(3L, 17L)).thenReturn(List.of(10L));
        when(roles.findByRoleIdIn(List.of(10L)))
                .thenReturn(List.of(role(10L, 3L, "ACTIVE")));
        when(rolePermissions.findByTenantIdAndRoleIdIn(3L, List.of(10L)))
                .thenReturn(List.of(
                        assignment(10L, 101L, 201L, "ALLOW"),
                        assignment(10L, 102L, 201L, "ALLOW"),
                        assignment(10L, 103L, 201L, "UNKNOWN")));
        when(resources.findAllById(List.of(101L, 102L, 103L))).thenReturn(List.of(
                resource(101L, 3L, "APP.WORK", false),
                resource(102L, 99L, "APP.APPS", true),
                resource(103L, 3L, "APP.ACTIVITY", true)));
        when(permissions.findAllById(List.of(201L)))
                .thenReturn(List.of(permission(201L, "VIEW")));
        when(principalGrants.findEffective(3L, 17L)).thenReturn(List.of(
                new PrincipalResourceGrantRepository.EffectiveGrant(
                        "grant-2", "APP", " ", "Invalid", "VIEW", "View", "ALLOW")));
        when(scopedDuties.capabilityPermissions(3L, 17L)).thenReturn(List.of());

        assertThat(resolver().resolve(3L, 17L)).isEmpty();
    }

    @Test
    void ignoresPermissionsFromInactiveOrCrossTenantRoles() {
        when(roleMembers.findRoleIds(3L, 17L)).thenReturn(List.of(10L, 11L));
        when(roles.findByRoleIdIn(List.of(10L, 11L))).thenReturn(List.of(
                role(10L, 3L, "INACTIVE"),
                role(11L, 99L, "ACTIVE")));
        when(principalGrants.findEffective(3L, 17L)).thenReturn(List.of());
        when(scopedDuties.capabilityPermissions(3L, 17L)).thenReturn(List.of());

        assertThat(resolver().resolve(3L, 17L)).isEmpty();
    }

    private EffectivePermissionKeyResolver resolver() {
        return new EffectivePermissionKeyResolver(
                roleMembers, roles, rolePermissions, resources, permissions,
                principalGrants, scopedDuties);
    }

    private Role role(Long roleId, Long tenantId, String status) {
        return Role.builder()
                .roleId(roleId)
                .tenantId(tenantId)
                .code("ROLE_" + roleId)
                .name("Role " + roleId)
                .status(status)
                .build();
    }

    private RolePermission assignment(
            Long roleId, Long resourceId, Long permissionId, String effect) {
        return RolePermission.builder()
                .tenantId(3L)
                .roleId(roleId)
                .resourceId(resourceId)
                .permissionId(permissionId)
                .effect(effect)
                .build();
    }

    private Resource resource(Long id, Long tenantId, String key, boolean enabled) {
        return Resource.builder()
                .resourceId(id)
                .tenantId(tenantId)
                .type("APP")
                .key(key)
                .name(key)
                .enabled(enabled)
                .build();
    }

    private Permission permission(Long id, String code) {
        return Permission.builder().permissionId(id).code(code).name(code).build();
    }

    private PermissionDTO permissionDto(String resourceKey, String code, String effect) {
        return PermissionDTO.builder()
                .resourceType("APP")
                .resourceKey(resourceKey)
                .resourceName(resourceKey)
                .permissionCode(code)
                .permissionName(code)
                .effect(effect)
                .build();
    }
}
