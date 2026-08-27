package com.dwp.services.auth.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public final class AuthenticatedUserResolver {

    private static final Set<String> ADMIN_ROLES =
            Set.of("ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN");
    private static final Set<String> IDENTITY_ADMIN_ROLES =
            Set.of("ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN", "IDENTITY_ADMIN");

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

    public static UUID requireSessionFamilyId(Authentication authentication) {
        Jwt jwt = requireJwt(authentication);
        try {
            return UUID.fromString(jwt.getClaimAsString("sid"));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BaseException(ErrorCode.TOKEN_INVALID);
        }
    }

    public static void requireTenantAdmin(Authentication authentication) {
        if (!hasTenantAdminRole(authentication)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }

    public static boolean hasTenantAdminRole(Authentication authentication) {
        return hasAnyRole(authentication, ADMIN_ROLES);
    }

    public static void requireIdentityAdmin(Authentication authentication) {
        if (!hasAnyRole(authentication, IDENTITY_ADMIN_ROLES)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }

    public static boolean hasAnyRole(Authentication authentication, Set<String> allowedRoles) {
        Jwt jwt = requireJwt(authentication);
        Object roles = jwt.getClaims().get("roles");
        return roles instanceof Collection<?> values
                && values.stream().map(String::valueOf).anyMatch(allowedRoles::contains);
    }

    private static Jwt requireJwt(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new BaseException(ErrorCode.AUTH_REQUIRED);
        }
        return jwt;
    }
}
