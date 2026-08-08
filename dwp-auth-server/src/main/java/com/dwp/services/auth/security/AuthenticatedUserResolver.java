package com.dwp.services.auth.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Set;

public final class AuthenticatedUserResolver {

    private static final Set<String> ADMIN_ROLES =
            Set.of("ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN");

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

    public static void requireTenantAdmin(Authentication authentication) {
        Jwt jwt = requireJwt(authentication);
        Object roles = jwt.getClaims().get("roles");
        if (!(roles instanceof Collection<?> values)
                || values.stream().map(String::valueOf).noneMatch(ADMIN_ROLES::contains)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }

    private static Jwt requireJwt(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new BaseException(ErrorCode.AUTH_REQUIRED);
        }
        return jwt;
    }
}
