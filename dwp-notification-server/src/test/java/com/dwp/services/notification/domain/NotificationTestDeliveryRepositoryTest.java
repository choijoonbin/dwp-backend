package com.dwp.services.notification.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTestDeliveryRepositoryTest {

    @Test
    void diagnosticInsertTargetsOnlyTheDedicatedReceipt() {
        assertThat(NotificationTestDeliveryRepository.INSERT_SQL)
                .contains("INSERT INTO ntf_test_delivery_receipts")
                .contains("requested_channels", "stage_results", "expires_at")
                .doesNotContain(
                        "ntf_user_notifications", "ntf_notifications",
                        "ntf_delivery_jobs", "ntf_outbox_events");
    }
}
