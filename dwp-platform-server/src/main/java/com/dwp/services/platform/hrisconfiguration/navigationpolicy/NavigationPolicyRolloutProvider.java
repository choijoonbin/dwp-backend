package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.RolloutMode;

public interface NavigationPolicyRolloutProvider {

    RuntimeEnrollment enrollment(long tenantId);

    record RuntimeEnrollment(boolean enrolled, RolloutMode mode) {
    }
}
