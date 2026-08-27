package com.dwp.services.auth.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedUserResolverTest {

    @Test
    void acceptsTenantAdministratorClaims() {
        assertThatCode(() -> AuthenticatedUserResolver.requireTenantAdmin(
                        authentication(List.of("EMPLOYEE", "TENANT_ADMIN"))))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsIdentityAdministratorForIdentityReadBoundary() {
        assertThatCode(() -> AuthenticatedUserResolver.requireIdentityAdmin(
                        authentication(List.of("WORKSPACE_MEMBER", "IDENTITY_ADMIN"))))
                .doesNotThrowAnyException();
    }

    @Test
    void identityAdministratorCannotCrossTenantRoleAdministrationBoundary() {
        assertThatThrownBy(() -> AuthenticatedUserResolver.requireTenantAdmin(
                        authentication(List.of("WORKSPACE_MEMBER", "IDENTITY_ADMIN"))))
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void rejectsNonAdministratorClaims() {
        assertThatThrownBy(() -> AuthenticatedUserResolver.requireTenantAdmin(
                        authentication(List.of("EMPLOYEE"))))
                .isInstanceOfSatisfying(
                        BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void resolvesTheAuthenticatedSessionFamilyBinding() {
        assertThat(AuthenticatedUserResolver.requireSessionFamilyId(
                authentication(List.of("PROVIDER_SUPPORT"))))
                .isEqualTo(UUID.fromString("40000000-0000-0000-0000-000000000001"));
    }

    private UsernamePasswordAuthenticationToken authentication(List<String> roles) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("7")
                .claim("roles", roles)
                .claim("sid", "40000000-0000-0000-0000-000000000001")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build();
        return new UsernamePasswordAuthenticationToken(jwt, "token");
    }
}
