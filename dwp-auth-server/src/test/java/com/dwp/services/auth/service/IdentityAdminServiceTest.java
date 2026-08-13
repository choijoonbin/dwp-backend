package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.IdentityAdminDtos;
import com.dwp.services.auth.entity.AuthSession;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.entity.RoleMember;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.AuthSessionRepository;
import com.dwp.services.auth.repository.IdentityAccessEvidenceRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityAdminServiceTest {

    private static final Long TENANT_ID = 3L;
    private static final Long ACTOR_ID = 7L;
    private static final Long TARGET_ID = 11L;

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final RoleMemberRepository roleMemberRepository = mock(RoleMemberRepository.class);
    private final AuthSessionRepository authSessionRepository = mock(AuthSessionRepository.class);
    private final IdentityAccessEvidenceRepository accessEvidenceRepository =
            mock(IdentityAccessEvidenceRepository.class);
    private final IdentityAuditService auditService = mock(IdentityAuditService.class);
    private final RoleDelegationPolicyService delegationPolicyService =
            mock(RoleDelegationPolicyService.class);
    private final IdentityAdminService service = new IdentityAdminService(
            userRepository,
            roleRepository,
            roleMemberRepository,
            authSessionRepository,
            accessEvidenceRepository,
            auditService,
            delegationPolicyService);

    @Test
    void identityAdministratorCanReadUsersWithoutReceivingRoleDelegation() {
        User target = user(2L, 4L);
        when(userRepository.findAll(
                        org.mockito.ArgumentMatchers.<Specification<User>>any(),
                        any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(target)));
        when(roleMemberRepository.findByTenantIdAndUserIdIn(TENANT_ID, List.of(TARGET_ID)))
                .thenReturn(List.of());
        when(roleRepository.findByRoleIdIn(List.of())).thenReturn(List.of());
        when(accessEvidenceRepository.effectiveAccess(TENANT_ID, List.of(TARGET_ID)))
                .thenReturn(Map.of());
        when(accessEvidenceRepository.sessionEvidence(TENANT_ID, List.of(TARGET_ID)))
                .thenReturn(Map.of());
        when(delegationPolicyService.findDirectDelegation(TENANT_ID, ACTOR_ID))
                .thenReturn(Optional.empty());
        when(delegationPolicyService.effectiveRoleCodesByUser(
                        TENANT_ID, List.of(TARGET_ID)))
                .thenReturn(Map.of(TARGET_ID, Set.of("WORKSPACE_MEMBER")));

        IdentityAdminDtos.PageResult<IdentityAdminDtos.UserAccessSummary> result =
                service.listUsers(TENANT_ID, ACTOR_ID, null, 0, 50);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().getFirst().roleManagement())
                .isEqualTo(new IdentityAdminDtos.RoleManagementSummary(
                        false, "ROLE_ASSIGNMENT_REQUIRES_TENANT_ADMIN"));
        verify(delegationPolicyService, never()).evaluateTarget(any(), any(), any(), any(), any());
    }

    @Test
    void identityAdministratorReceivesNoAssignableTenantRoles() {
        when(delegationPolicyService.findDirectDelegation(TENANT_ID, ACTOR_ID))
                .thenReturn(Optional.empty());

        assertThat(service.listRoles(TENANT_ID, ACTOR_ID)).isEmpty();
    }

    @Test
    void replacesOnlyDelegatedRolesRevokesSessionsAndWritesAudit() {
        User user = user(2L, 4L);
        Role workspace = role(20L, "WORKSPACE_MEMBER");
        Role hrAdmin = role(30L, "HR_ADMIN");
        RoleMember membership = membership(100L, workspace.getRoleId());
        AuthSession session = AuthSession.builder()
                .sessionId(UUID.randomUUID())
                .tenantId(TENANT_ID)
                .userId(TARGET_ID)
                .build();

        RoleDelegationPolicyService.DelegationContext context = context(workspace, hrAdmin);
        when(delegationPolicyService.resolve(TENANT_ID, ACTOR_ID)).thenReturn(context);
        when(userRepository.findByUserIdAndTenantId(TARGET_ID, TENANT_ID))
                .thenReturn(Optional.of(user));
        when(roleMemberRepository.findByTenantIdAndUserId(TENANT_ID, TARGET_ID))
                .thenReturn(List.of(membership));
        when(roleRepository.findByRoleIdIn(List.of(workspace.getRoleId())))
                .thenReturn(List.of(workspace));
        when(delegationPolicyService.effectiveRoleCodesByUser(TENANT_ID, List.of(TARGET_ID)))
                .thenReturn(Map.of(TARGET_ID, Set.of("WORKSPACE_MEMBER")));
        when(delegationPolicyService.evaluateTarget(
                        context, ACTOR_ID, TARGET_ID, "ACTIVE", Set.of("WORKSPACE_MEMBER")))
                .thenReturn(new RoleDelegationPolicyService.RoleManagementDecision(true, "ALLOWED"));
        when(delegationPolicyService.evaluateRoleSet(
                        Set.of("WORKSPACE_MEMBER"),
                        Set.of("WORKSPACE_MEMBER"),
                        Set.of("WORKSPACE_MEMBER", "HR_ADMIN")))
                .thenReturn(new RoleDelegationPolicyService.RoleSetDecision(true, "ALLOWED"));
        when(authSessionRepository.findByTenantIdAndUserIdAndRevokedAtIsNull(
                        TENANT_ID, TARGET_ID))
                .thenReturn(List.of(session));
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        IdentityAdminDtos.UserAccessSummary result = service.replaceRoles(
                TENANT_ID,
                ACTOR_ID,
                "corr-1",
                TARGET_ID,
                request(Set.of("workspace_member", "hr_admin"), 2L, 4L));

        assertThat(result.roles()).containsExactly("HR_ADMIN", "WORKSPACE_MEMBER");
        assertThat(result.accessRevision()).isEqualTo(3L);
        assertThat(session.getRevokedAt()).isNotNull();
        verify(roleMemberRepository, never()).deleteAll(any());
        verify(roleMemberRepository).saveAll(argThat(values -> {
            List<RoleMember> added = new java.util.ArrayList<>();
            values.forEach(added::add);
            return added.size() == 1
                    && TENANT_ID.equals(added.get(0).getTenantId())
                    && TARGET_ID.equals(added.get(0).getUserId())
                    && hrAdmin.getRoleId().equals(added.get(0).getRoleId());
        }));
        verify(auditService).success(
                eq(TENANT_ID),
                eq(ACTOR_ID),
                eq("identity.user-roles.replaced"),
                eq("USER_ACCESS"),
                eq(String.valueOf(TARGET_ID)),
                eq("corr-1"),
                any(),
                any());
    }

    @Test
    void rejectsRoleOutsideDelegationBoundaryAndAuditsTheAttempt() {
        Role workspace = role(20L, "WORKSPACE_MEMBER");
        RoleDelegationPolicyService.DelegationContext context = context(workspace);
        prepareManageableTarget(context, workspace);

        assertThatThrownBy(() -> service.replaceRoles(
                        TENANT_ID,
                        ACTOR_ID,
                        "corr-denied",
                        TARGET_ID,
                        request(Set.of("WORKSPACE_MEMBER", "PROVIDER_ADMIN"), 2L, 4L)))
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(auditService).denied(
                eq(TENANT_ID),
                eq(ACTOR_ID),
                eq("identity.user-roles.rejected"),
                eq("USER_ACCESS"),
                eq(String.valueOf(TARGET_ID)),
                eq("corr-denied"),
                eq("ROLE_OUTSIDE_DELEGATION_BOUNDARY"),
                any());
        verify(roleMemberRepository, never()).saveAll(any());
    }

    @Test
    void rejectsProtectedTargetBeforeChangingMemberships() {
        Role workspace = role(20L, "WORKSPACE_MEMBER");
        RoleDelegationPolicyService.DelegationContext context = context(workspace);
        when(delegationPolicyService.resolve(TENANT_ID, ACTOR_ID)).thenReturn(context);
        when(userRepository.findByUserIdAndTenantId(TARGET_ID, TENANT_ID))
                .thenReturn(Optional.of(user(2L, 4L)));
        when(roleMemberRepository.findByTenantIdAndUserId(TENANT_ID, TARGET_ID))
                .thenReturn(List.of(membership(100L, workspace.getRoleId())));
        when(roleRepository.findByRoleIdIn(List.of(workspace.getRoleId())))
                .thenReturn(List.of(workspace));
        Set<String> effective = Set.of("WORKSPACE_MEMBER", "TENANT_ADMIN");
        when(delegationPolicyService.effectiveRoleCodesByUser(TENANT_ID, List.of(TARGET_ID)))
                .thenReturn(Map.of(TARGET_ID, effective));
        when(delegationPolicyService.evaluateTarget(
                        context, ACTOR_ID, TARGET_ID, "ACTIVE", effective))
                .thenReturn(new RoleDelegationPolicyService.RoleManagementDecision(
                        false, "PROTECTED_ROLE"));

        assertThatThrownBy(() -> service.replaceRoles(
                        TENANT_ID,
                        ACTOR_ID,
                        "corr-protected",
                        TARGET_ID,
                        request(Set.of("WORKSPACE_MEMBER"), 2L, 4L)))
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(roleMemberRepository, never()).saveAll(any());
    }

    @Test
    void rejectsBaselineRemovalOrSeparationOfDutiesViolation() {
        Role workspace = role(20L, "WORKSPACE_MEMBER");
        RoleDelegationPolicyService.DelegationContext context = context(workspace);
        prepareManageableTarget(context, workspace);
        when(delegationPolicyService.evaluateRoleSet(
                        Set.of("WORKSPACE_MEMBER"), Set.of("WORKSPACE_MEMBER"), Set.of()))
                .thenReturn(new RoleDelegationPolicyService.RoleSetDecision(
                        false, "BASELINE_ROLE_REQUIRED"));

        assertThatThrownBy(() -> service.replaceRoles(
                        TENANT_ID,
                        ACTOR_ID,
                        "corr-baseline",
                        TARGET_ID,
                        request(Set.of(), 2L, 4L)))
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        verify(auditService).denied(
                eq(TENANT_ID), eq(ACTOR_ID), any(), any(), any(), eq("corr-baseline"),
                eq("BASELINE_ROLE_REQUIRED"), any());
    }

    @Test
    void rejectsAStaleAccessRevisionBeforeChangingMemberships() {
        when(delegationPolicyService.resolve(TENANT_ID, ACTOR_ID))
                .thenReturn(context(role(20L, "WORKSPACE_MEMBER")));
        when(userRepository.findByUserIdAndTenantId(TARGET_ID, TENANT_ID))
                .thenReturn(Optional.of(user(3L, 5L)));

        assertThatThrownBy(() -> service.replaceRoles(
                        TENANT_ID,
                        ACTOR_ID,
                        null,
                        TARGET_ID,
                        request(Set.of("WORKSPACE_MEMBER"), 2L, 5L)))
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        verify(roleMemberRepository, never()).findByTenantIdAndUserId(any(), any());
    }

    private void prepareManageableTarget(
            RoleDelegationPolicyService.DelegationContext context,
            Role workspace) {
        when(delegationPolicyService.resolve(TENANT_ID, ACTOR_ID)).thenReturn(context);
        when(userRepository.findByUserIdAndTenantId(TARGET_ID, TENANT_ID))
                .thenReturn(Optional.of(user(2L, 4L)));
        when(roleMemberRepository.findByTenantIdAndUserId(TENANT_ID, TARGET_ID))
                .thenReturn(List.of(membership(100L, workspace.getRoleId())));
        when(roleRepository.findByRoleIdIn(List.of(workspace.getRoleId())))
                .thenReturn(List.of(workspace));
        when(delegationPolicyService.effectiveRoleCodesByUser(TENANT_ID, List.of(TARGET_ID)))
                .thenReturn(Map.of(TARGET_ID, Set.of("WORKSPACE_MEMBER")));
        when(delegationPolicyService.evaluateTarget(
                        context, ACTOR_ID, TARGET_ID, "ACTIVE", Set.of("WORKSPACE_MEMBER")))
                .thenReturn(new RoleDelegationPolicyService.RoleManagementDecision(true, "ALLOWED"));
    }

    private RoleDelegationPolicyService.DelegationContext context(Role... roles) {
        Map<String, RoleDelegationPolicyService.AssignableRole> options = new LinkedHashMap<>();
        for (Role role : roles) {
            options.put(role.getCode(), new RoleDelegationPolicyService.AssignableRole(
                    role,
                    role.getCode().equals("WORKSPACE_MEMBER") ? "WORKSPACE" : "PEOPLE",
                    role.getCode().equals("WORKSPACE_MEMBER") ? "BASELINE" : "DELEGATED",
                    "DIRECT",
                    options.size(),
                    Set.of()));
        }
        return new RoleDelegationPolicyService.DelegationContext(Set.of("TENANT_ADMIN"), options);
    }

    private IdentityAdminDtos.ReplaceUserRolesRequest request(
            Set<String> roles,
            Long accessRevision,
            Long version) {
        return new IdentityAdminDtos.ReplaceUserRolesRequest(
                roles, "Approved workforce access change", accessRevision, version);
    }

    private User user(Long accessRevision, Long version) {
        return User.builder()
                .userId(TARGET_ID)
                .tenantId(TENANT_ID)
                .displayName("Target User")
                .email("target@example.com")
                .status("ACTIVE")
                .mfaEnabled(true)
                .accessRevision(accessRevision)
                .version(version)
                .build();
    }

    private Role role(Long roleId, String code) {
        return Role.builder()
                .roleId(roleId)
                .tenantId(TENANT_ID)
                .code(code)
                .name(code)
                .status("ACTIVE")
                .build();
    }

    private RoleMember membership(Long id, Long roleId) {
        return RoleMember.builder()
                .roleMemberId(id)
                .tenantId(TENANT_ID)
                .roleId(roleId)
                .userId(TARGET_ID)
                .build();
    }
}
