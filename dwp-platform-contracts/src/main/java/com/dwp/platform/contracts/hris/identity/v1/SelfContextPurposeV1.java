package com.dwp.platform.contracts.hris.identity.v1;

/** Neutral ABI.v1 only; not an HTTP endpoint or authentication mechanism. */
public enum SelfContextPurposeV1 {
    SELF_PROFILE_READ(Audience.HRIS_HRM),
    SELF_PERFORMANCE_READ(Audience.HRIS_PER),
    SELF_ATTENDANCE_READ(Audience.HRIS_TIM),
    SELF_PAY_READ(Audience.HRIS_PAY),
    SELF_HRIS_HOME_READ(Audience.HRIS_SYS);

    private final Audience audience;

    SelfContextPurposeV1(Audience audience) {
        this.audience = audience;
    }

    public Audience audience() {
        return audience;
    }

    public enum Audience { HRIS_HRM, HRIS_PER, HRIS_TIM, HRIS_PAY, HRIS_SYS }
}
