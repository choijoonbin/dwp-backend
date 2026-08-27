package com.dwp.services.auth.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Resolves the immutable principal plane from the Auth-owned user record. */
@Component
public class DurableIdentityPlaneGuard {

    private final UserRepository userRepository;

    public DurableIdentityPlaneGuard(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public boolean isProvider(Authentication authentication) {
        return "PROVIDER".equals(requirePlane(authentication));
    }

    public void requireTenant(Authentication authentication) {
        if (!"TENANT".equals(requirePlane(authentication))) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Provider control-plane identities cannot use tenant work APIs.");
        }
    }

    private String requirePlane(Authentication authentication) {
        Jwt jwt = requireJwt(authentication);
        Long userId = positiveLong(jwt.getSubject());
        Object tenantClaim = jwt.getClaims().get("tenant_id");
        Long tenantId = positiveLong(tenantClaim == null ? null : String.valueOf(tenantClaim));
        User user = userRepository.findByUserIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.TOKEN_INVALID));
        String plane = user.getIdentityPlane();
        if (!"PROVIDER".equals(plane) && !"TENANT".equals(plane)) {
            throw new BaseException(ErrorCode.TOKEN_INVALID);
        }
        return plane;
    }

    private Jwt requireJwt(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new BaseException(ErrorCode.AUTH_REQUIRED);
        }
        return jwt;
    }

    private Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException | NullPointerException exception) {
            throw new BaseException(ErrorCode.TOKEN_INVALID);
        }
    }
}
