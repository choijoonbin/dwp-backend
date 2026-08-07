package com.dwp.services.auth.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

public final class TenantContextResolver {

    private TenantContextResolver() {
    }

    public static Long requireTenantId(String headerValue, Authentication authentication) {
        Long headerTenantId = parse(headerValue, ErrorCode.INVALID_INPUT_VALUE);
        Long tokenTenantId = null;
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            Object claim = jwt.getClaim("tenant_id");
            tokenTenantId = claim == null ? null : parse(String.valueOf(claim), ErrorCode.TOKEN_INVALID);
        }

        if (headerTenantId != null && tokenTenantId != null && !headerTenantId.equals(tokenTenantId)) {
            throw new BaseException(ErrorCode.TENANT_MISMATCH);
        }
        if (headerTenantId != null) return headerTenantId;
        if (tokenTenantId != null) return tokenTenantId;
        throw new BaseException(ErrorCode.TENANT_MISSING);
    }

    private static Long parse(String value, ErrorCode errorCode) {
        if (value == null || value.isBlank()) return null;
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new BaseException(errorCode);
        }
    }
}
