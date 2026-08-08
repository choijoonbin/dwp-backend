package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.IdentityAdminDtos;
import com.dwp.services.auth.entity.AuthSession;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.entity.RoleMember;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.AuthSessionRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    private final IdentityAuditService auditService = mock(IdentityAuditService.class);
    private final IdentityAdminService service = new IdentityAdminService(
            userRepository,
            roleRepository,
            roleMemberRepository,
            authSessionRepository,
            auditService);

    @Test
    void replacesRoleSetRevokesSessionsAndWritesAudit() {
        User user = user(2L, 4L);
        Role employee = role(20L, "EMPLOYEE");
        Role admin = role(10L, "ADMIN");
        RoleMember membership = membership(100L, employee.getRoleId());
        AuthSession session = AuthSession.builder()
                .sessionId(UUID.randomUUID())
                .tenantId(TENANT_ID)
                .userId(TARGET_ID)
                .build();

        when(userRepository.findByUserIdAndTenantId(TARGET_ID, TENANT_ID))
                .thenReturn(Optional.of(user));
        when(roleRepository.findByTenantIdAndCodeIn(TENANT_ID, Set.of("ADMIN")))
                .thenReturn(List.of(admin));
        when(roleMemberRepository.findByTenantIdAndUserId(TENANT_ID, TARGET_ID))
                .thenReturn(List.of(membership));
        when(roleRepository.findByRoleIdIn(List.of(employee.getRoleId())))
                .thenReturn(List.of(employee));
        when(roleRepository.findByTenantIdAndCodeForUpdate(TENANT_ID, "ADMIN"))
                .thenReturn(Optional.of(admin));
        when(authSessionRepository.findByTenantIdAndUserIdAndRevokedAtIsNull(
                        TENANT_ID, TARGET_ID))
                .thenReturn(List.of(session));
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        IdentityAdminDtos.UserAccessSummary result = service.replaceRoles(
                TENANT_ID,
                ACTOR_ID,
                "corr-1",
                TARGET_ID,
                request(Set.of("admin"), 2L, 4L));

        assertThat(result.roles()).containsExactly("ADMIN");
        assertThat(result.accessRevision()).isEqualTo(3L);
        assertThat(session.getRevokedAt()).isNotNull();
        verify(roleMemberRepository).deleteAll(List.of(membership));
        verify(roleMemberRepository).saveAll(argThat(values -> {
            List<RoleMember> added = new java.util.ArrayList<>();
            values.forEach(added::add);
            return added.size() == 1
                    && TENANT_ID.equals(added.get(0).getTenantId())
                    && TARGET_ID.equals(added.get(0).getUserId())
                    && admin.getRoleId().equals(added.get(0).getRoleId());
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
    void rejectsSelfRoleChangesBeforeLoadingTheTarget() {
        assertThatThrownBy(() -> service.replaceRoles(
                        TENANT_ID,
                        ACTOR_ID,
                        null,
                        ACTOR_ID,
                        request(Set.of("ADMIN"), 0L, 0L)))
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        verify(userRepository, never()).findByUserIdAndTenantId(any(), any());
    }

    @Test
    void rejectsRemovingTheLastAdministratorUnderARoleLock() {
        User user = user(1L, 2L);
        Role admin = role(10L, "ADMIN");
        RoleMember membership = membership(100L, admin.getRoleId());
        when(userRepository.findByUserIdAndTenantId(TARGET_ID, TENANT_ID))
                .thenReturn(Optional.of(user));
        when(roleMemberRepository.findByTenantIdAndUserId(TENANT_ID, TARGET_ID))
                .thenReturn(List.of(membership));
        when(roleRepository.findByRoleIdIn(List.of(admin.getRoleId())))
                .thenReturn(List.of(admin));
        when(roleRepository.findByTenantIdAndCodeForUpdate(TENANT_ID, "ADMIN"))
                .thenReturn(Optional.of(admin));
        when(roleMemberRepository.countByTenantIdAndRoleId(TENANT_ID, admin.getRoleId()))
                .thenReturn(1L);

        assertThatThrownBy(() -> service.replaceRoles(
                        TENANT_ID,
                        ACTOR_ID,
                        null,
                        TARGET_ID,
                        request(Set.of(), 1L, 2L)))
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        verify(roleRepository).findByTenantIdAndCodeForUpdate(TENANT_ID, "ADMIN");
        verify(roleMemberRepository, never()).deleteAll(any());
    }

    @Test
    void rejectsAStaleAccessRevisionBeforeChangingMemberships() {
        when(userRepository.findByUserIdAndTenantId(TARGET_ID, TENANT_ID))
                .thenReturn(Optional.of(user(3L, 5L)));

        assertThatThrownBy(() -> service.replaceRoles(
                        TENANT_ID,
                        ACTOR_ID,
                        null,
                        TARGET_ID,
                        request(Set.of("ADMIN"), 2L, 5L)))
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        verify(roleRepository, never()).findByTenantIdAndCodeIn(any(), any());
    }

    @Test
    void rejectsUnknownOrInactiveTenantRoles() {
        when(userRepository.findByUserIdAndTenantId(TARGET_ID, TENANT_ID))
                .thenReturn(Optional.of(user(0L, 0L)));
        when(roleRepository.findByTenantIdAndCodeIn(TENANT_ID, Set.of("UNKNOWN")))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.replaceRoles(
                        TENANT_ID,
                        ACTOR_ID,
                        null,
                        TARGET_ID,
                        request(Set.of("UNKNOWN"), 0L, 0L)))
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    private IdentityAdminDtos.ReplaceUserRolesRequest request(
            Set<String> roles,
            Long accessRevision,
            Long version) {
        return new IdentityAdminDtos.ReplaceUserRolesRequest(roles, accessRevision, version);
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
