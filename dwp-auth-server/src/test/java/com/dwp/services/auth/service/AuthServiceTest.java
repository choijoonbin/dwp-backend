package com.dwp.services.auth.service;

import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AuthPolicyResponse;
import com.dwp.services.auth.dto.LoginRequest;
import com.dwp.services.auth.dto.OidcUserInfo;
import com.dwp.services.auth.entity.Tenant;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.entity.UserAccount;
import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.RolePermission;
import com.dwp.services.auth.repository.PermissionRepository;
import com.dwp.services.auth.repository.PrincipalResourceGrantRepository;
import com.dwp.services.auth.repository.DirectoryGroupMemberRepository;
import com.dwp.services.auth.repository.DirectoryGroupRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RolePermissionRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.TenantRepository;
import com.dwp.services.auth.repository.UserAccountRepository;
import com.dwp.services.auth.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final RoleMemberRepository roleMembers = mock(RoleMemberRepository.class);
    private final DirectoryGroupRepository groups = mock(DirectoryGroupRepository.class);
    private final DirectoryGroupMemberRepository groupMembers =
            mock(DirectoryGroupMemberRepository.class);
    private final RolePermissionRepository rolePermissions = mock(RolePermissionRepository.class);
    private final ResourceRepository resources = mock(ResourceRepository.class);
    private final PermissionRepository permissions = mock(PermissionRepository.class);
    private final PrincipalResourceGrantRepository principalGrants =
            mock(PrincipalResourceGrantRepository.class);
    private final AuthSessionService sessions = mock(AuthSessionService.class);
    private final AuthPolicyService policies = mock(AuthPolicyService.class);
    private final IdentityAccountService identityAccounts = mock(IdentityAccountService.class);
    private final LoginAttemptService attempts = mock(LoginAttemptService.class);
    private final AppGovernanceService appGovernance = mock(AppGovernanceService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(
                users, accounts, tenants, roles, roleMembers, groups, groupMembers, rolePermissions,
                resources, permissions, principalGrants, sessions, policies, identityAccounts, attempts,
                appGovernance, encoder);
        when(tenants.findById(1L)).thenReturn(Optional.of(Tenant.builder()
                .tenantId(1L).code("default").name("Default").status("ACTIVE").build()));
        when(policies.getPolicy(1L)).thenReturn(AuthPolicyResponse.builder()
                .tenantId(1L)
                .defaultLoginType("LOCAL")
                .allowedLoginTypes(List.of("LOCAL", "SSO"))
                .localLoginEnabled(true)
                .ssoLoginEnabled(true)
                .build());
        when(roleMembers.findRoleIds(1L, 10L)).thenReturn(List.of());
        when(groupMembers.findByTenantIdAndUserId(1L, 10L)).thenReturn(List.of());
        when(appGovernance.resourceRoles(1L, 10L)).thenReturn(List.of());
        when(principalGrants.findEffective(1L, 10L)).thenReturn(List.of());
        when(sessions.create(any(), any(), any(), any())).thenReturn(
                new AuthSessionService.IssuedSession("access-token", 3600));
    }

    @Test
    void localLoginUsesNormalizedCompanyEmail() {
        UserAccount account = UserAccount.builder()
                .userAccountId(20L)
                .tenantId(1L)
                .userId(10L)
                .providerType("LOCAL")
                .providerId("local")
                .principal("employee@example.com")
                .passwordHash(encoder.encode("Valid-password-1!"))
                .status("ACTIVE")
                .build();
        User user = User.builder()
                .userId(10L)
                .tenantId(1L)
                .displayName("Employee")
                .status("ACTIVE")
                .build();
        when(accounts.findLocalForAuthentication(1L, "employee@example.com"))
                .thenReturn(Optional.of(account));
        when(users.findByUserIdAndTenantId(10L, 1L)).thenReturn(Optional.of(user));

        LoginRequest login = new LoginRequest();
        login.setTenantId("1");
        login.setEmail("  Employee@Example.COM ");
        login.setPassword("Valid-password-1!");

        AuthenticatedSession result = service.login(login, request);

        assertThat(result.accessToken()).isEqualTo("access-token");
        verify(accounts).findLocalForAuthentication(1L, "employee@example.com");
        verify(attempts).success(account, "employee@example.com", request);
    }

    @Test
    void malformedEmailUsesGenericCredentialFailurePath() {
        LoginRequest login = new LoginRequest();
        login.setTenantId("1");
        login.setEmail("not-an-email");
        login.setPassword("Valid-password-1!");

        assertThatThrownBy(() -> service.login(login, request))
                .isInstanceOf(BaseException.class);

        verify(attempts).failure(
                null, 1L, "LOCAL", "local", "not-an-email", "INVALID_EMAIL", request);
    }

    @Test
    void meResponseCarriesTheStablePeopleProjectionIdentity() {
        UUID personPublicId = UUID.fromString("3edde887-9716-8950-e7a0-045998101987");
        User user = User.builder()
                .userId(10L)
                .tenantId(1L)
                .personPublicId(personPublicId)
                .displayName("Employee")
                .status("ACTIVE")
                .build();
        when(users.findByUserIdAndTenantId(10L, 1L)).thenReturn(Optional.of(user));

        var response = service.getMe(10L, 1L);

        assertThat(response.getPersonPublicId()).isEqualTo(personPublicId);
    }

    @Test
    void combinesAuthOwnedDirectEntitlementsWithRolePermissions() {
        when(principalGrants.findEffective(1L, 10L)).thenReturn(List.of(
                new PrincipalResourceGrantRepository.EffectiveGrant(
                        UUID.randomUUID().toString(), "APP", "APP.MAIL",
                        "Mail and calendar", "VIEW", "View", "ALLOW")));

        var result = service.getPermissions(10L, 1L);

        assertThat(result).singleElement().satisfies(permission -> {
            assertThat(permission.getResourceKey()).isEqualTo("APP.MAIL");
            assertThat(permission.getPermissionCode()).isEqualTo("VIEW");
            assertThat(permission.getEffect()).isEqualTo("ALLOW");
        });
    }

    @Test
    void denyOverridesRoleAndDirectAllowsForTheSameAuthority() {
        RolePermission roleAllow = RolePermission.builder()
                .tenantId(1L).roleId(2L).resourceId(3L).permissionId(4L)
                .effect("ALLOW").build();
        RolePermission roleDeny = RolePermission.builder()
                .tenantId(1L).roleId(5L).resourceId(3L).permissionId(4L)
                .effect("DENY").build();
        when(roleMembers.findRoleIds(1L, 10L)).thenReturn(List.of(2L, 5L));
        when(rolePermissions.findByTenantIdAndRoleIdIn(1L, List.of(2L, 5L)))
                .thenReturn(List.of(roleAllow, roleDeny));
        when(resources.findAllById(List.of(3L, 3L))).thenReturn(List.of(
                Resource.builder().resourceId(3L).tenantId(1L).type("APP")
                        .key("APP.APPROVALS").name("Approvals").enabled(true).build()));
        when(permissions.findAllById(List.of(4L, 4L))).thenReturn(List.of(
                Permission.builder().permissionId(4L).code("VIEW").name("View").build()));
        when(principalGrants.findEffective(1L, 10L)).thenReturn(List.of(
                new PrincipalResourceGrantRepository.EffectiveGrant(
                        UUID.randomUUID().toString(), "APP", "APP.APPROVALS",
                        "Approvals", "VIEW", "View", "ALLOW")));

        assertThat(service.getPermissions(10L, 1L)).isEmpty();
    }

    @Test
    void firstOidcLoginLinksVerifiedEmailThenAuthenticatesByIssuerAndSubject() {
        OidcUserInfo userInfo = new OidcUserInfo(
                "https://idp.example.com", "subject-123", "Employee@Example.com", true, "Employee");
        User user = User.builder()
                .userId(10L)
                .tenantId(1L)
                .displayName("Employee")
                .email("employee@example.com")
                .status("ACTIVE")
                .build();
        UserAccount linked = UserAccount.builder()
                .userAccountId(21L)
                .tenantId(1L)
                .userId(10L)
                .providerType("OIDC")
                .providerId("entra")
                .issuerUri("https://idp.example.com")
                .principal("subject-123")
                .status("ACTIVE")
                .build();
        when(accounts.findByTenantIdAndProviderTypeAndIssuerUriAndPrincipal(
                1L, "OIDC", "https://idp.example.com", "subject-123"))
                .thenReturn(Optional.empty());
        when(users.findByTenantIdAndEmailNormalized(1L, "employee@example.com"))
                .thenReturn(Optional.of(user));
        when(identityAccounts.linkOidcAccount(
                user, "entra", "https://idp.example.com", "subject-123"))
                .thenReturn(linked);
        when(users.findByUserIdAndTenantId(10L, 1L)).thenReturn(Optional.of(user));

        AuthenticatedSession result = service.loginWithOidc(1L, "entra", userInfo, request);

        assertThat(result.accessToken()).isEqualTo("access-token");
        verify(identityAccounts).linkOidcAccount(
                user, "entra", "https://idp.example.com", "subject-123");
        verify(attempts).success(linked, "subject-123", request);
    }
}
