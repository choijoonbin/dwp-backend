package com.dwp.services.notification.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationSchedulingConfigurationTest {

    @Test
    void blockingAndLatencySensitiveJobsUseDistinctExecutors() {
        NotificationSchedulingConfiguration configuration =
                new NotificationSchedulingConfiguration();
        var outbox = configuration.notificationOutboxTaskScheduler();
        var retention = configuration.notificationRetentionTaskScheduler();
        var reconciliation = configuration.notificationReconciliationTaskScheduler();
        var sse = configuration.notificationSseTaskScheduler();

        assertThat(outbox).isNotSameAs(retention).isNotSameAs(sse);
        assertThat(retention).isNotSameAs(reconciliation);
        assertThat(reconciliation).isNotSameAs(sse);
        assertThat(outbox.getThreadNamePrefix()).isEqualTo("dwp-notification-outbox-");
        assertThat(sse.getThreadNamePrefix()).isEqualTo("dwp-notification-sse-");
    }
}
