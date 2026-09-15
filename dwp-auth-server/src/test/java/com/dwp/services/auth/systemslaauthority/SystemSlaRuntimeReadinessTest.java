package com.dwp.services.auth.systemslaauthority;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class SystemSlaRuntimeReadinessTest {
    @Test
    void reportsReadyOnlyWhenTheDistributedReplayAuthorityResponds() {
        assertThat(new SystemSlaRuntimeReadiness(() -> { }).health().getStatus()).isEqualTo(Status.UP);

        var unavailable = new SystemSlaRuntimeReadiness(() -> {
            throw SystemSlaJson.unavailable();
        }).health();
        assertThat(unavailable.getStatus()).isEqualTo(Status.DOWN);
        assertThat(unavailable.getDetails()).containsEntry("reason", "REPLAY_AUTHORITY_UNAVAILABLE");
    }
}
