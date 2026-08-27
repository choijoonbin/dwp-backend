package com.dwp.services.auth.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DurableIdentityPlaneGuardTest {

    private final UserRepository users = mock(UserRepository.class);
    private final DurableIdentityPlaneGuard guard = new DurableIdentityPlaneGuard(users);

    @Test
    void tenantPlaneComesFromTheDurableUserRecord() {
        User tenantUser = User.builder()
                .userId(71L)
                .tenantId(9L)
                .identityPlane("TENANT")
                .build();
        when(users.findByUserIdAndTenantId(71L, 9L)).thenReturn(Optional.of(tenantUser));

        guard.requireTenant(authentication(71L, 9L));
    }

    @Test
    void providerPlaneCannotEnterTenantWorkEvenWithoutAProviderRoleClaim() {
        User providerUser = User.builder()
                .userId(900001L)
                .tenantId(1L)
                .identityPlane("PROVIDER")
                .build();
        when(users.findByUserIdAndTenantId(900001L, 1L)).thenReturn(Optional.of(providerUser));

        assertThatThrownBy(() -> guard.requireTenant(authentication(900001L, 1L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void missingDurableIdentityInvalidatesTheTokenContext() {
        when(users.findByUserIdAndTenantId(71L, 9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireTenant(authentication(71L, 9L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.TOKEN_INVALID));
    }

    private Authentication authentication(Long userId, Long tenantId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .issuedAt(Instant.parse("2026-08-26T03:00:00Z"))
                .expiresAt(Instant.parse("2026-08-26T04:00:00Z"))
                .claim("tenant_id", tenantId.toString())
                .build();
        return new TestingAuthenticationToken(jwt, null);
    }
}
