package com.dwp.services.auth.security;

import com.dwp.services.auth.repository.AuthSessionRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AuthSessionJwtValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_SESSION = new OAuth2Error(
            "invalid_token",
            "The authentication session is missing, expired, or revoked.",
            null);

    private final AuthSessionRepository authSessionRepository;

    public AuthSessionJwtValidator(AuthSessionRepository authSessionRepository) {
        this.authSessionRepository = authSessionRepository;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String tokenId = jwt.getId();
        if (tokenId == null || tokenId.isBlank()) {
            return OAuth2TokenValidatorResult.failure(INVALID_SESSION);
        }
        boolean active = authSessionRepository
                .existsByTokenIdAndRevokedAtIsNullAndExpiresAtAfter(tokenId, Instant.now());
        return active
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(INVALID_SESSION);
    }
}
