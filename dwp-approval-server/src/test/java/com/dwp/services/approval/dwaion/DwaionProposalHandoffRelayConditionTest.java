package com.dwp.services.approval.dwaion;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DwaionProposalHandoffRelayConditionTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(DwaionProposalHandoffOutboxRepository.class,
                    () -> mock(DwaionProposalHandoffOutboxRepository.class))
            .withBean(DwaionProposalHandoffObserverClient.class,
                    () -> mock(DwaionProposalHandoffObserverClient.class))
            .withUserConfiguration(DwaionProposalHandoffRelay.class);

    @Test
    void missingPropertyKeepsTheProductionDefaultEnabled() {
        context.run(result -> assertThat(result)
                .hasSingleBean(DwaionProposalHandoffRelay.class));
    }

    @Test
    void explicitFalseDisablesTheScheduledRelay() {
        context.withPropertyValues("dwp.approval.dwaion-handoff.enabled=false")
                .run(result -> assertThat(result)
                        .doesNotHaveBean(DwaionProposalHandoffRelay.class));
    }
}
