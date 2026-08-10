package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AccessGovernanceDtos;
import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.entity.RoleMember;
import com.dwp.services.auth.entity.RolePermission;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.AuthSessionRepository;
import com.dwp.services.auth.repository.DirectoryGroupMemberRepository;
import com.dwp.services.auth.repository.DirectoryGroupRepository;
import com.dwp.services.auth.repository.GroupRoleAssignmentRepository;
import com.dwp.services.auth.repository.PermissionRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RolePermissionRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessGovernanceServiceTest {

    private static final Long TENANT_ID = 7L;
    private static final Long USER_ID = 21L;

    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final ResourceRepository resourceRepository = mock(ResourceRepository.class);
    private final PermissionRepository permissionRepository = mock(PermissionRepository.class);
    private final RolePermissionRepository rolePermissionRepository = mock(RolePermissionRepository.class);
    private final RoleMemberRepository roleMemberRepository = mock(RoleMemberRepository.class);
    private final DirectoryGroupRepository groupRepository = mock(DirectoryGroupRepository.class);
    private final DirectoryGroupMemberRepository groupMemberRepository = mock(DirectoryGroupMemberRepository.class);
    private final GroupRoleAssignmentRepository groupRoleRepository = mock(GroupRoleAssignmentRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AccessGovernanceService service = new AccessGovernanceService(
            roleRepository,
            resourceRepository,
            permissionRepository,
            rolePermissionRepository,
            roleMemberRepository,
            groupRepository,
            groupMemberRepository,
            groupRoleRepository,
            userRepository,
            mock(AuthSessionRepository.class),
            mock(IdentityAuditService.class));

    @Test
    void effectivePermissionNamesOnlyRolesThatContributedThatPermission() {
        Role workRole = role(10L, "WORK_REVIEWER");
        Role peopleRole = role(11L, "PEOPLE_READER");
        Resource work = resource(30L, "APP.WORK");
        Resource people = resource(31L, "DATA.PEOPLE");
        Permission view = Permission.builder().permissionId(40L).code("VIEW").name("View").build();
        RolePermission workGrant = grant(workRole, work, view);
        RolePermission peopleGrant = grant(peopleRole, people, view);

        when(userRepository.findByUserIdAndTenantId(USER_ID, TENANT_ID))
                .thenReturn(Optional.of(User.builder()
                        .userId(USER_ID).tenantId(TENANT_ID).displayName("A User")
                        .accessRevision(3L).build()));
        when(roleMemberRepository.findByTenantIdAndUserId(TENANT_ID, USER_ID))
                .thenReturn(List.of(member(workRole), member(peopleRole)));
        when(roleRepository.findByRoleIdIn(List.of(10L, 11L)))
                .thenReturn(List.of(workRole, peopleRole));
        when(roleRepository.findByRoleIdIn(List.of())).thenReturn(List.of());
        when(groupMemberRepository.findByTenantIdAndUserId(TENANT_ID, USER_ID))
                .thenReturn(List.of());
        when(rolePermissionRepository.findByTenantIdAndRoleIdInAndEffect(
                TENANT_ID, List.of(10L, 11L), "ALLOW"))
                .thenReturn(List.of(workGrant, peopleGrant));
        when(rolePermissionRepository.findByTenantIdAndRoleIdInAndEffect(
                TENANT_ID, List.of(10L, 11L), "DENY"))
                .thenReturn(List.of());
        when(resourceRepository.findAllById(List.of(30L, 31L)))
                .thenReturn(List.of(work, people));
        when(permissionRepository.findAllById(List.of(40L)))
                .thenReturn(List.of(view));

        AccessGovernanceDtos.EffectiveAccess result = service.effectiveAccess(TENANT_ID, USER_ID);

        assertThat(result.permissions()).extracting(AccessGovernanceDtos.EffectivePermission::resourceKey)
                .containsExactly("APP.WORK", "DATA.PEOPLE");
        assertThat(result.permissions().get(0).grantedByRoles()).containsExactly("WORK_REVIEWER");
        assertThat(result.permissions().get(1).grantedByRoles()).containsExactly("PEOPLE_READER");
    }

    private Role role(Long id, String code) {
        return Role.builder().roleId(id).tenantId(TENANT_ID).code(code).name(code)
                .status("ACTIVE").build();
    }

    private Resource resource(Long id, String key) {
        return Resource.builder().resourceId(id).tenantId(TENANT_ID).type("APP")
                .key(key).name(key).enabled(true).build();
    }

    private RolePermission grant(Role role, Resource resource, Permission permission) {
        return RolePermission.builder().tenantId(TENANT_ID).roleId(role.getRoleId())
                .resourceId(resource.getResourceId()).permissionId(permission.getPermissionId())
                .effect("ALLOW").build();
    }

    private RoleMember member(Role role) {
        return RoleMember.builder().tenantId(TENANT_ID).userId(USER_ID)
                .roleId(role.getRoleId()).build();
    }
}
