package com.dwp.services.auth.security;

import com.dwp.services.auth.entity.AuthSession;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.repository.AuthSessionRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.UserRepository;
import com.dwp.services.auth.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthSessionJwtValidatorTest {

    private final AuthSessionRepository repository = mock(AuthSessionRepository.class);
    private final RoleMemberRepository roleMemberRepository = mock(RoleMemberRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuthSessionJwtValidator validator = new AuthSessionJwtValidator(
            repository, roleMemberRepository, roleRepository, userRepository);

    {
        when(roleMemberRepository.findRoleIds(1L, 1L)).thenReturn(List.of());
        when(roleRepository.findByRoleIdIn(List.of())).thenReturn(List.of());
        when(userRepository.findByUserIdAndTenantId(1L, 1L)).thenReturn(Optional.of(
                User.builder().userId(1L).tenantId(1L).displayName("User")
                        .identityPlane("TENANT").build()));
    }

    @Test
    void acceptsAnActiveSessionWithMatchingIdentityAndFamily() {
        AuthSession session = activeSession();
        when(repository.findByTokenId("session-token-id")).thenReturn(Optional.of(session));

        assertThat(validator.validate(jwt(session)).hasErrors()).isFalse();
    }

    @Test
    void rejectsAnIdleExpiredSession() {
        AuthSession session = activeSession();
        session.setIdleExpiresAt(Instant.now().minusSeconds(1));
        when(repository.findByTokenId("session-token-id")).thenReturn(Optional.of(session));

        assertThat(validator.validate(jwt(session)).hasErrors()).isTrue();
    }

    @Test
    void acceptsASupersededTokenOnlyInsideItsGraceWindow() {
        AuthSession session = activeSession();
        session.setSupersededAt(Instant.now().minusSeconds(5));
        session.setSupersededExpiresAt(Instant.now().plusSeconds(5));
        when(repository.findByTokenId("session-token-id")).thenReturn(Optional.of(session));

        assertThat(validator.validate(jwt(session)).hasErrors()).isFalse();

        session.setSupersededExpiresAt(Instant.now().minusSeconds(1));
        assertThat(validator.validate(jwt(session)).hasErrors()).isTrue();
    }

    @Test
    void rejectsARegistryRowThatDoesNotMatchTheJwtTenant() {
        AuthSession session = activeSession();
        when(repository.findByTokenId("session-token-id")).thenReturn(Optional.of(session));
        Jwt jwt = jwt(session, "2");

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void rejectsAnOtherwiseMatchingSessionWithMixedEffectiveRolePlanes() {
        AuthSession session = activeSession();
        when(repository.findByTokenId("session-token-id")).thenReturn(Optional.of(session));
        when(roleMemberRepository.findRoleIds(1L, 1L)).thenReturn(List.of(10L, 11L));
        when(roleRepository.findByRoleIdIn(List.of(10L, 11L))).thenReturn(List.of(
                role(10L, "PROVIDER_ADMIN"),
                role(11L, "WORKSPACE_MEMBER")));

        Jwt mixed = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("1")
                .claim("jti", session.getTokenId())
                .claim("tenant_id", "1")
                .claim("sid", session.getSessionFamilyId().toString())
                .claim("roles", List.of("PROVIDER_ADMIN", "WORKSPACE_MEMBER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        assertThat(validator.validate(mixed).hasErrors()).isTrue();
    }

    @Test
    void rejectsAProviderRoleAssignedToATenantPlaneIdentity() {
        AuthSession session = activeSession();
        when(repository.findByTokenId("session-token-id")).thenReturn(Optional.of(session));
        when(roleMemberRepository.findRoleIds(1L, 1L)).thenReturn(List.of(10L));
        when(roleRepository.findByRoleIdIn(List.of(10L))).thenReturn(List.of(
                role(10L, "PROVIDER_ADMIN")));
        Jwt providerClaim = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("1")
                .claim("jti", session.getTokenId())
                .claim("tenant_id", "1")
                .claim("sid", session.getSessionFamilyId().toString())
                .claim("roles", List.of("PROVIDER_ADMIN"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        assertThat(validator.validate(providerClaim).hasErrors()).isTrue();
    }

    private Role role(Long id, String code) {
        return Role.builder()
                .roleId(id)
                .tenantId(1L)
                .code(code)
                .status("ACTIVE")
                .build();
    }

    private AuthSession activeSession() {
        Instant now = Instant.now();
        return AuthSession.builder()
                .sessionId(UUID.randomUUID())
                .sessionFamilyId(UUID.randomUUID())
                .tokenId("session-token-id")
                .tenantId(1L)
                .userId(1L)
                .sessionStartedAt(now.minusSeconds(60))
                .issuedAt(now.minusSeconds(60))
                .lastSeenAt(now.minusSeconds(10))
                .idleExpiresAt(now.plusSeconds(300))
                .expiresAt(now.plusSeconds(3600))
                .build();
    }

    private Jwt jwt(AuthSession session) {
        return jwt(session, String.valueOf(session.getTenantId()));
    }

    private Jwt jwt(AuthSession session, String tenantId) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(String.valueOf(session.getUserId()))
                .claim("jti", session.getTokenId())
                .claim("tenant_id", tenantId)
                .claim("sid", session.getSessionFamilyId().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }
}
