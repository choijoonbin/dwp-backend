package com.dwp.services.auth.systemslaauthority;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/** Readiness for the replay-safe Auth side of SYSTEM_SLA authority evaluation. */
public final class SystemSlaRuntimeReadiness implements HealthIndicator {
    private final Runnable replayProbe;

    public SystemSlaRuntimeReadiness(SystemSlaReplayStore replay) {
        this(replay == null ? null : replay::ready);
    }

    SystemSlaRuntimeReadiness(Runnable replayProbe) {
        if (replayProbe == null) throw new IllegalStateException("SYSTEM_SLA replay authority is required");
        this.replayProbe = replayProbe;
    }

    @Override
    public Health health() {
        try {
            replayProbe.run();
            return Health.up().withDetail("contract", "DWP_AUTH_APPROVAL_SYSTEM_SLA_V1").build();
        } catch (RuntimeException unavailable) {
            return Health.down().withDetail("contract", "DWP_AUTH_APPROVAL_SYSTEM_SLA_V1")
                    .withDetail("reason", "REPLAY_AUTHORITY_UNAVAILABLE").build();
        }
    }
}
