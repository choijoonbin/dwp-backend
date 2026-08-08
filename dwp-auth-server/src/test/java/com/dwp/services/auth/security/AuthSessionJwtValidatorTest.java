package com.dwp.services.auth.security;

import com.dwp.services.auth.entity.AuthSession;
import com.dwp.services.auth.repository.AuthSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthSessionJwtValidatorTest {

    private final AuthSessionRepository repository = mock(AuthSessionRepository.class);
    private final AuthSessionJwtValidator validator = new AuthSessionJwtValidator(repository);

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
