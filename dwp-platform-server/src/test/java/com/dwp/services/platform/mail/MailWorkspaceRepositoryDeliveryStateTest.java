package com.dwp.services.platform.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MailWorkspaceRepositoryDeliveryStateTest {

    @Test
    void unknownProviderOutcomeNeverAppearsAsAFailedTimelineEvent() {
        assertThat(MailWorkspaceRepository.deliveryAttentionState("RESULT_UNKNOWN"))
                .isEqualTo("RESULT_UNKNOWN");
        assertThat(MailWorkspaceRepository.deliveryAttentionState("FAILED"))
                .isEqualTo("FAILED");
    }
}
