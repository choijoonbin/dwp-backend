package com.dwp.services.platform.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MailWorkspaceRepositoryDeliveryStateTest {

    @Test
    void unknownProviderOutcomeNeverAppearsAsAFailedTimelineEvent() {
        assertThat(MailWorkspaceRepository.deliveryAttentionState("UNKNOWN"))
                .isEqualTo("UNKNOWN");
        assertThat(MailWorkspaceRepository.deliveryAttentionState("FAILED"))
                .isEqualTo("FAILED");
    }
}
