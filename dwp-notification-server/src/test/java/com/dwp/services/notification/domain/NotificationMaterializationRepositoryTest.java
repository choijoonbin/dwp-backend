package com.dwp.services.notification.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
class NotificationMaterializationRepositoryTest {

    @Test
    void eachMaterializationIntentHasItsOwnOutboxIdentity() {
        UUID sourceEventId = UUID.randomUUID();
        UUID firstIntent = UUID.randomUUID();
        UUID secondIntent = UUID.randomUUID();

        assertThat(NotificationOutboxEventKeys.materialized(
                sourceEventId, firstIntent))
                .isNotEqualTo(NotificationOutboxEventKeys.materialized(
                        sourceEventId, secondIntent))
                .contains(sourceEventId.toString(), firstIntent.toString());
    }

    @Test
    void inboxReadsRenderedContentOnlyFromTheRecipientProjection() {
        assertThat(NotificationQueryRepository.INBOX_SELECT)
                .contains("user_notification.actor_ref")
                .contains("user_notification.action_payload::text")
                .contains("user_notification.safe_body")
                .contains("user_notification.first_activity_at")
                .contains("user_notification.occurrence_count")
                .doesNotContain("                   notification.actor_ref,")
                .doesNotContain("                   notification.action_payload::text")
                .doesNotContain("                   notification.safe_body,");
    }
}
