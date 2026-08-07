package com.dwp.services.auth.security;

import com.dwp.services.auth.repository.AuthSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthSessionJwtValidatorTest {

    private final AuthSessionRepository repository = mock(AuthSessionRepository.class);
    private final AuthSessionJwtValidator validator = new AuthSessionJwtValidator(repository);

    @Test
    void acceptsOnlyAnActiveRegisteredSession() {
        when(repository.existsByTokenIdAndRevokedAtIsNullAndExpiresAtAfter(
                org.mockito.ArgumentMatchers.eq("session-token-id"),
                org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(true);

        assertThat(validator.validate(jwt("session-token-id")).hasErrors()).isFalse();
    }

    @Test
    void rejectsARevokedOrUnknownSession() {
        when(repository.existsByTokenIdAndRevokedAtIsNullAndExpiresAtAfter(
                org.mockito.ArgumentMatchers.eq("session-token-id"),
                org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(false);

        assertThat(validator.validate(jwt("session-token-id")).hasErrors()).isTrue();
    }

    private Jwt jwt(String tokenId) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("1")
                .claim("jti", tokenId)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }
}
