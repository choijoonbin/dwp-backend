package com.dwp.services.auth.identity;

import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.RolePermission;
import com.dwp.services.auth.repository.PermissionRepository;
import com.dwp.services.auth.repository.PrincipalResourceGrantRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RolePermissionRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.service.ScopedAdminDutyEvidenceService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the exact permission keys exposed to internal tenant-plane consumers.
 * A deny from any effective source wins over every allow for the same key.
 */
@Component
public class EffectivePermissionKeyResolver {

    private final RoleMemberRepository roleMembers;
    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;
    private final ResourceRepository resources;
    private final PermissionRepository permissions;
    private final PrincipalResourceGrantRepository principalGrants;
    private final ScopedAdminDutyEvidenceService scopedDuties;

    public EffectivePermissionKeyResolver(
            RoleMemberRepository roleMembers,
            RoleRepository roles,
            RolePermissionRepository rolePermissions,
            ResourceRepository resources,
            PermissionRepository permissions,
            PrincipalResourceGrantRepository principalGrants,
            ScopedAdminDutyEvidenceService scopedDuties) {
        this.roleMembers = roleMembers;
        this.roles = roles;
        this.rolePermissions = rolePermissions;
        this.resources = resources;
        this.permissions = permissions;
        this.principalGrants = principalGrants;
        this.scopedDuties = scopedDuties;
    }

    @Transactional(readOnly = true)
    public List<String> resolve(Long tenantId, Long userId) {
        List<Long> membershipRoleIds = roleMembers.findRoleIds(tenantId, userId).stream()
                .distinct()
                .toList();
        Set<Long> eligibleRoleIds = membershipRoleIds.isEmpty()
                ? Set.of()
                : roles.findByRoleIdIn(membershipRoleIds).stream()
                        .filter(role -> tenantId.equals(role.getTenantId()))
                        .filter(role -> "ACTIVE".equalsIgnoreCase(role.getStatus()))
                        .map(role -> role.getRoleId())
                        .collect(Collectors.toSet());
        List<Long> roleIds = membershipRoleIds.stream()
                .filter(eligibleRoleIds::contains)
                .toList();
        List<RolePermission> assignments = roleIds.isEmpty()
                ? List.of()
                : rolePermissions.findByTenantIdAndRoleIdIn(tenantId, roleIds);
        Map<Long, Resource> resourcesById = resources.findAllById(
                        assignments.stream().map(RolePermission::getResourceId).distinct().toList())
                .stream()
                .filter(resource -> Boolean.TRUE.equals(resource.getEnabled()))
                .filter(resource -> resource.getTenantId() == null
                        || tenantId.equals(resource.getTenantId()))
                .collect(Collectors.toMap(Resource::getResourceId, Function.identity()));
        Map<Long, Permission> permissionsById = permissions.findAllById(
                        assignments.stream().map(RolePermission::getPermissionId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Permission::getPermissionId, Function.identity()));

        Set<String> allowed = new LinkedHashSet<>();
        Set<String> denied = new LinkedHashSet<>();
        assignments.forEach(assignment -> collect(
                assignment.getEffect(),
                resourcesById.get(assignment.getResourceId()),
                permissionsById.get(assignment.getPermissionId()),
                allowed,
                denied));
        principalGrants.findEffective(tenantId, userId).forEach(grant -> collect(
                grant.effect(), grant.resourceKey(), grant.permissionCode(), allowed, denied));
        scopedDuties.capabilityPermissions(tenantId, userId).forEach(permission -> collect(
                permission.getEffect(), permission.getResourceKey(),
                permission.getPermissionCode(), allowed, denied));
        allowed.removeAll(denied);
        return allowed.stream().sorted().toList();
    }

    private void collect(
            String effect,
            Resource resource,
            Permission permission,
            Set<String> allowed,
            Set<String> denied) {
        if (resource == null || permission == null) return;
        collect(effect, resource.getKey(), permission.getCode(), allowed, denied);
    }

    private void collect(
            String effect,
            String resourceKey,
            String permissionCode,
            Set<String> allowed,
            Set<String> denied) {
        String key = key(resourceKey, permissionCode);
        if (key == null) return;
        if ("DENY".equalsIgnoreCase(effect)) {
            denied.add(key);
            allowed.remove(key);
        } else if ("ALLOW".equalsIgnoreCase(effect) && !denied.contains(key)) {
            allowed.add(key);
        }
    }

    private String key(String resourceKey, String permissionCode) {
        if (resourceKey == null || resourceKey.isBlank()
                || permissionCode == null || permissionCode.isBlank()) return null;
        return resourceKey.strip().toUpperCase(Locale.ROOT) + ":"
                + permissionCode.strip().toUpperCase(Locale.ROOT);
    }
}
