package com.dwp.services.auth.productaccess;

import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.RolePermission;
import com.dwp.services.auth.repository.PermissionRepository;
import com.dwp.services.auth.repository.PrincipalResourceGrantRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RolePermissionRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
final class DwpHrisExplicitDenyEvidenceReader implements HrisExplicitDenyEvidenceReader {

    private final RoleMemberRepository roleMembers;
    private final RolePermissionRepository rolePermissions;
    private final ResourceRepository resources;
    private final PermissionRepository permissions;
    private final PrincipalResourceGrantRepository principalGrants;

    DwpHrisExplicitDenyEvidenceReader(
            RoleMemberRepository roleMembers,
            RolePermissionRepository rolePermissions,
            ResourceRepository resources,
            PermissionRepository permissions,
            PrincipalResourceGrantRepository principalGrants) {
        this.roleMembers = roleMembers;
        this.rolePermissions = rolePermissions;
        this.resources = resources;
        this.permissions = permissions;
        this.principalGrants = principalGrants;
    }

    @Override
    public Set<String> load(Long tenantId, Long subjectId) {
        List<Long> roleIds = roleMembers.findRoleIds(tenantId, subjectId);
        List<RolePermission> assignments = roleIds.isEmpty() ? List.of()
                : rolePermissions.findByTenantIdAndRoleIdInAndEffect(tenantId, roleIds, "DENY");
        Map<Long, Resource> resourceById = resources.findAllById(
                        assignments.stream().map(RolePermission::getResourceId).toList()).stream()
                .filter(resource -> Boolean.TRUE.equals(resource.getEnabled()))
                .filter(resource -> resource.getTenantId() == null
                        || tenantId.equals(resource.getTenantId()))
                .collect(Collectors.toMap(Resource::getResourceId, Function.identity()));
        Map<Long, Permission> permissionById = permissions.findAllById(
                        assignments.stream().map(RolePermission::getPermissionId).toList()).stream()
                .collect(Collectors.toMap(Permission::getPermissionId, Function.identity()));
        LinkedHashSet<String> denied = new LinkedHashSet<>();
        assignments.forEach(assignment -> {
            Resource resource = resourceById.get(assignment.getResourceId());
            Permission permission = permissionById.get(assignment.getPermissionId());
            if (resource != null && permission != null) {
                denied.add(key(resource.getKey(), permission.getCode()));
            }
        });
        principalGrants.findEffective(tenantId, subjectId).stream()
                .filter(grant -> "DENY".equalsIgnoreCase(grant.effect()))
                .map(grant -> key(grant.resourceKey(), grant.permissionCode()))
                .forEach(denied::add);
        return Set.copyOf(denied);
    }

    private String key(String resource, String permission) {
        String normalizedResource = resource.trim().toUpperCase(Locale.ROOT);
        if ("APP.HRIS".equals(normalizedResource)) normalizedResource = "APP.HCM";
        return normalizedResource + ":" + permission.trim().toUpperCase(Locale.ROOT);
    }
}
