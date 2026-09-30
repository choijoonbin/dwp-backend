package com.dwp.services.auth.controller;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.security.TenantPermissionAuthorization;
import com.dwp.services.auth.service.AccessGovernanceService;
import com.dwp.services.auth.service.AccessReviewService;
import com.dwp.services.auth.service.DelegatedAdminScopeService;
import com.dwp.services.auth.service.DirectoryAdminService;
import com.dwp.services.auth.service.IdentityAdminService;
import com.dwp.services.auth.service.IdentityAuditService;
import com.dwp.services.auth.service.PrivilegedAccessService;
import com.dwp.services.auth.scim.ScimCredentialService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantAdminControllerAuthorizationTest {

    private static final long TENANT = 42L;
    private static final long ACTOR = 7L;

    @Test
    void adminReadsUseExactPersistedViewPermissionsWithoutRoleNames() {
        TenantPermissionAuthorization authorization = mock(TenantPermissionAuthorization.class);
        Authentication user = authentication(TENANT, ACTOR, List.of("EMPLOYEE"));

        IdentityAdminService identity = mock(IdentityAdminService.class);
        new IdentityAdminController(identity, mock(IdentityAuditService.class), authorization)
                .roles(user, String.valueOf(TENANT));
        verify(authorization).require(
                TENANT, ACTOR, TenantPermissionAuthorization.IDENTITY_DIRECTORY, "VIEW");

        DirectoryAdminService directory = mock(DirectoryAdminService.class);
        new DirectoryAdminController(directory, authorization)
                .users(user, String.valueOf(TENANT), null, "ACTIVE", 0, 50);
        verify(authorization, times(2)).require(
                TENANT, ACTOR, TenantPermissionAuthorization.IDENTITY_DIRECTORY, "VIEW");

        AccessGovernanceService governance = mock(AccessGovernanceService.class);
        new AccessGovernanceController(governance, authorization)
                .roles(user, String.valueOf(TENANT));
        verify(authorization).require(
                TENANT, ACTOR, TenantPermissionAuthorization.ACCESS_GOVERNANCE, "VIEW");

        AccessReviewService reviews = mock(AccessReviewService.class);
        new AccessReviewController(reviews, authorization)
                .campaigns(user, String.valueOf(TENANT));
        verify(authorization).require(
                TENANT, ACTOR, TenantPermissionAuthorization.ACCESS_REVIEWS, "VIEW");

        PrivilegedAccessService privileged = mock(PrivilegedAccessService.class);
        new PrivilegedAccessController(
                privileged, mock(DelegatedAdminScopeService.class), authorization)
                .policies(user, String.valueOf(TENANT));
        verify(authorization).require(
                TENANT, ACTOR, TenantPermissionAuthorization.PRIVILEGED_ACCESS, "VIEW");

        ScimCredentialService scim = mock(ScimCredentialService.class);
        new ScimConnectorAdminController(scim, authorization)
                .list(user, String.valueOf(TENANT));
        verify(authorization).require(
                TENANT, ACTOR, TenantPermissionAuthorization.IDENTITY_PROVISIONING, "VIEW");
    }

    @Test
    void adminCommandsSeparateManageFromApproval() {
        TenantPermissionAuthorization authorization = mock(TenantPermissionAuthorization.class);
        Authentication user = authentication(TENANT, ACTOR, List.of("EMPLOYEE"));

        IdentityAdminService identity = mock(IdentityAdminService.class);
        new IdentityAdminController(identity, mock(IdentityAuditService.class), authorization)
                .replaceRoles(user, String.valueOf(TENANT), "corr", 8L, null);
        verify(authorization).require(
                TENANT, ACTOR, TenantPermissionAuthorization.IDENTITY_DIRECTORY, "MANAGE");

        DirectoryAdminService directory = mock(DirectoryAdminService.class);
        new DirectoryAdminController(directory, authorization)
                .createGroup(user, String.valueOf(TENANT), "corr", null);
        verify(authorization, times(2)).require(
                TENANT, ACTOR, TenantPermissionAuthorization.IDENTITY_DIRECTORY, "MANAGE");

        AccessGovernanceService governance = mock(AccessGovernanceService.class);
        new AccessGovernanceController(governance, authorization)
                .createRole(user, String.valueOf(TENANT), "corr", null);
        verify(authorization).require(
                TENANT, ACTOR, TenantPermissionAuthorization.ACCESS_GOVERNANCE, "MANAGE");

        AccessReviewService reviews = mock(AccessReviewService.class);
        new AccessReviewController(reviews, authorization).decide(
                user, String.valueOf(TENANT), "corr",
                UUID.randomUUID(), UUID.randomUUID(), null);
        verify(authorization).require(
                TENANT, ACTOR, TenantPermissionAuthorization.ACCESS_REVIEWS, "APPROVE");

        PrivilegedAccessService privileged = mock(PrivilegedAccessService.class);
        new PrivilegedAccessController(
                privileged, mock(DelegatedAdminScopeService.class), authorization)
                .decide(user, String.valueOf(TENANT), "corr", UUID.randomUUID(), null);
        verify(authorization).require(
                TENANT, ACTOR, TenantPermissionAuthorization.PRIVILEGED_ACCESS, "APPROVE");

        ScimCredentialService scim = mock(ScimCredentialService.class);
        new ScimConnectorAdminController(scim, authorization).create(
                user, String.valueOf(TENANT), "corr", mock(HttpServletResponse.class), null);
        verify(authorization).require(
                TENANT, ACTOR, TenantPermissionAuthorization.IDENTITY_PROVISIONING, "MANAGE");
    }

    @Test
    void deniedExactPermissionStopsTheOwnerService() {
        TenantPermissionAuthorization authorization = mock(TenantPermissionAuthorization.class);
        IdentityAdminService service = mock(IdentityAdminService.class);
        doThrow(new BaseException(ErrorCode.FORBIDDEN)).when(authorization).require(
                TENANT, ACTOR, TenantPermissionAuthorization.IDENTITY_DIRECTORY, "VIEW");
        IdentityAdminController controller = new IdentityAdminController(
                service, mock(IdentityAuditService.class), authorization);

        assertThatThrownBy(() -> controller.roles(
                authentication(TENANT, ACTOR, List.of("TENANT_ADMIN")),
                String.valueOf(TENANT)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(service);
    }

    @Test
    void tokenTenantCannotAuthorizeAnotherTenantHeader() {
        TenantPermissionAuthorization authorization = mock(TenantPermissionAuthorization.class);
        IdentityAdminService service = mock(IdentityAdminService.class);
        IdentityAdminController controller = new IdentityAdminController(
                service, mock(IdentityAuditService.class), authorization);

        assertThatThrownBy(() -> controller.roles(
                authentication(TENANT, ACTOR, List.of("TENANT_ADMIN")), "99"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TENANT_MISMATCH));
        verifyNoInteractions(service, authorization);
    }

    @Test
    void privilegedSelfServiceDoesNotRequireAnAdminPermission() {
        TenantPermissionAuthorization authorization = mock(TenantPermissionAuthorization.class);
        PrivilegedAccessService service = mock(PrivilegedAccessService.class);
        PrivilegedAccessController controller = new PrivilegedAccessController(
                service, mock(DelegatedAdminScopeService.class), authorization);
        Authentication user = authentication(TENANT, ACTOR, List.of("WORKSPACE_MEMBER"));

        controller.myEligibilities(user, String.valueOf(TENANT));
        controller.myRequests(user, String.valueOf(TENANT));
        controller.requestActivation(user, String.valueOf(TENANT), "corr", null);

        verify(service).myEligibilities(TENANT, ACTOR);
        verify(service).requests(TENANT, ACTOR, false);
        verify(service).requestActivation(TENANT, ACTOR, "SESSION", "corr", null);
        verifyNoInteractions(authorization);
    }

    @Test
    void selfRevokeElevatesOnlyWithCurrentExactManagePermission() {
        TenantPermissionAuthorization authorization = mock(TenantPermissionAuthorization.class);
        PrivilegedAccessService service = mock(PrivilegedAccessService.class);
        PrivilegedAccessController controller = new PrivilegedAccessController(
                service, mock(DelegatedAdminScopeService.class), authorization);
        Authentication user = authentication(TENANT, ACTOR, List.of("TENANT_ADMIN"));
        UUID requestId = UUID.randomUUID();
        when(authorization.can(
                TENANT, ACTOR, TenantPermissionAuthorization.PRIVILEGED_ACCESS, "MANAGE"))
                .thenReturn(false);

        controller.revoke(user, String.valueOf(TENANT), "corr", requestId, null);

        verify(service).revoke(TENANT, ACTOR, false, "corr", requestId, null);
    }

    private Authentication authentication(
            long tenantId,
            long actorId,
            List<String> roles) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(String.valueOf(actorId))
                .issuedAt(Instant.parse("2026-09-29T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-29T01:00:00Z"))
                .claim("tenant_id", tenantId)
                .claim("roles", roles)
                .build();
        return new TestingAuthenticationToken(jwt, null);
    }
}
