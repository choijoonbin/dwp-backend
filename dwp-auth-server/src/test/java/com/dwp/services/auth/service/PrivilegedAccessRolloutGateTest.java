package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivilegedAccessRolloutGateTest {

    private final PrivilegedAccessRolloutGate gate = new PrivilegedAccessRolloutGate();

    @Test
    void activationHasNoEnvironmentOverrideAndFailsClosed() {
        assertThatThrownBy(gate::requireActivationEnabled)
                .isInstanceOfSatisfying(BaseException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(error.getMessage()).isEqualTo(
                            PrivilegedAccessRolloutGate.DISABLED_REASON);
                });
    }

    @Test
    void policyCannotEnableJitOrEmergencyModes() {
        assertThatThrownBy(() -> gate.requirePolicyRemainsDisabled("APPROVAL", "DISABLED"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> gate.requirePolicyRemainsDisabled("DISABLED", "DUAL_APPROVAL"))
                .isInstanceOf(BaseException.class);

        gate.requirePolicyRemainsDisabled("DISABLED", "DISABLED");
    }
}
