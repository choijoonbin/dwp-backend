package com.dwp.services.notification.operations;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationExternalCapabilityReadinessTest {

    @Test
    void remainsTruthfullyBlockedUntilPeopleSnapshotAndProvidersExist() {
        var health = new NotificationExternalCapabilityReadiness().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("state", "BLOCKED_EXTERNAL")
                .containsEntry("peopleAudienceSnapshot", "NOT_IMPLEMENTED");
        assertThat(health.getDetails().get("providers").toString())
                .contains("email=DISABLED", "slack=DISABLED");
    }
}
