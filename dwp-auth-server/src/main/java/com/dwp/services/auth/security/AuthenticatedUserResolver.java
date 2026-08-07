package com.dwp.services.auth.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

public final class AuthenticatedUserResolver {

    private AuthenticatedUserResolver() {
    }

    public static Long requireUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new BaseException(ErrorCode.AUTH_REQUIRED);
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new BaseException(ErrorCode.TOKEN_INVALID);
        }
    }
}
