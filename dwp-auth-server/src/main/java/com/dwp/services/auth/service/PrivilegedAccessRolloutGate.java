package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Component;

/**
 * Release kill switch for role elevation.
 *
 * <p>Scoped JIT cannot be enabled until scope, expiry and mutable authorization revision are
 * enforced end to end by every downstream PEP. This gate deliberately has no environment
 * override: re-enablement requires a reviewed code and database migration.</p>
 */
@Component
public final class PrivilegedAccessRolloutGate {

    static final String DISABLED_REASON =
            "Privileged access activation is disabled for this release.";

    public void requireActivationEnabled() {
        throw new BaseException(ErrorCode.FORBIDDEN, DISABLED_REASON);
    }

    public void requirePolicyRemainsDisabled(String activationMode, String emergencyMode) {
        if (!"DISABLED".equals(activationMode) || !"DISABLED".equals(emergencyMode)) {
            throw new BaseException(ErrorCode.FORBIDDEN, DISABLED_REASON);
        }
    }
}
