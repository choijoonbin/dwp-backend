package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AccessGovernanceDtos;
import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.DirectoryGroup;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessGovernanceServiceTest {

    private static final Long TENANT_ID = 7L;
    private static final Long USER_ID = 21L;
    private static final Long ACTOR_ID = 8L;

    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final ResourceRepository resourceRepository = mock(ResourceRepository.class);
    private final PermissionRepository permissionRepository = mock(PermissionRepository.class);
    private final RolePermissionRepository rolePermissionRepository = mock(RolePermissionRepository.class);
    private final RoleMemberRepository roleMemberRepository = mock(RoleMemberRepository.class);
    private final DirectoryGroupRepository groupRepository = mock(DirectoryGroupRepository.class);
    private final DirectoryGroupMemberRepository groupMemberRepository = mock(DirectoryGroupMemberRepository.class);
    private final GroupRoleAssignmentRepository groupRoleRepository = mock(GroupRoleAssignmentRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final IdentityAuditService auditService = mock(IdentityAuditService.class);
    private final RoleDelegationPolicyService delegationPolicyService =
            mock(RoleDelegationPolicyService.class);
    private final DelegatedAdminScopeService delegatedScopeService =
            mock(DelegatedAdminScopeService.class);
    private final GroupRoleConflictGuard groupRoleConflictGuard =
            mock(GroupRoleConflictGuard.class);
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
            auditService,
            delegationPolicyService,
            delegatedScopeService,
            groupRoleConflictGuard);

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

    @Test
    void tenantGovernanceCannotMutateSystemRoleDefinitions() {
        Role systemRole = Role.builder()
                .roleId(90L)
                .tenantId(TENANT_ID)
                .code("TENANT_ADMIN")
                .name("Tenant administrator")
                .roleType("SYSTEM")
                .status("ACTIVE")
                .privileged(true)
                .assignableToGroups(false)
                .version(0L)
                .build();
        when(roleRepository.findByRoleIdAndTenantId(90L, TENANT_ID))
                .thenReturn(Optional.of(systemRole));
        when(delegationPolicyService.resolve(TENANT_ID, ACTOR_ID))
                .thenReturn(new RoleDelegationPolicyService.DelegationContext(
                        Set.of("TENANT_ADMIN"), Map.of()));

        AccessGovernanceDtos.UpdateRoleRequest request =
                new AccessGovernanceDtos.UpdateRoleRequest(
                        "Renamed", "Changed", "ACTIVE", true, false, 0L);

        assertThatThrownBy(() -> service.updateRole(
                        TENANT_ID, ACTOR_ID, "corr-system", 90L, request))
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_STATE));

        verify(auditService).denied(
                eq(TENANT_ID), eq(ACTOR_ID), eq("access.governance.rejected"),
                eq("ROLE"), eq("90"), eq("corr-system"),
                eq("SYSTEM_ROLE_IMMUTABLE"), any());
    }

    @Test
    void rejectsGroupRoleAssignmentWhenAnExistingMemberViolatesSod() {
        DirectoryGroup group = DirectoryGroup.builder()
                .groupId(30L)
                .tenantId(TENANT_ID)
                .displayName("Audit team")
                .status("ACTIVE")
                .build();
        Role role = role(40L, "HR_ADMIN");
        when(groupRepository.findByGroupIdAndTenantIdForUpdate(30L, TENANT_ID))
                .thenReturn(Optional.of(group));
        when(roleRepository.findByRoleIdAndTenantId(40L, TENANT_ID))
                .thenReturn(Optional.of(role));
        when(delegationPolicyService.resolve(TENANT_ID, ACTOR_ID))
                .thenReturn(new RoleDelegationPolicyService.DelegationContext(
                        Set.of("TENANT_ADMIN"),
                        Map.of("HR_ADMIN", new RoleDelegationPolicyService.AssignableRole(
                                role, "PEOPLE", "DELEGATED", "DIRECT", 20,
                                Set.of("AUDITOR")))));
        when(groupRoleConflictGuard.evaluateRoleAssignment(
                        TENANT_ID, 30L, "HR_ADMIN"))
                .thenReturn(Optional.of(new GroupRoleConflictGuard.Violation(
                        USER_ID,
                        "ROLE_CONFLICT_AUDIT_INDEPENDENCE",
                        List.of("AUDITOR", "WORKSPACE_MEMBER"),
                        List.of("HR_ADMIN"))));

        AccessGovernanceDtos.CreateGroupRoleAssignmentRequest request =
                new AccessGovernanceDtos.CreateGroupRoleAssignmentRequest(
                        30L, 40L, "ACTIVE", "TENANT", null, null, null,
                        "Assign the approved people administration boundary.");

        assertThatThrownBy(() -> service.createGroupAssignment(
                        TENANT_ID, ACTOR_ID, "corr-sod", request))
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        verify(groupRoleRepository, org.mockito.Mockito.never()).saveAndFlush(any());
        verify(auditService).denied(
                eq(TENANT_ID), eq(ACTOR_ID), eq("access.governance.rejected"),
                eq("DIRECTORY_GROUP"), eq("30"), eq("corr-sod"),
                eq("ROLE_CONFLICT_AUDIT_INDEPENDENCE"), any());
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
