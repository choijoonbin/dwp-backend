package com.dwp.services.messaging.meeting;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MeetingProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MeetingProviderConfiguration.class)
            .withBean(MeetingProperties.class);

    @Test
    void registersDisabledProviderWhenNoAdapterIsAvailable() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(MeetingProvider.class);
            assertThat(context.getBean(MeetingProvider.class))
                    .isInstanceOf(DisabledMeetingProvider.class);
        });
    }

    @Test
    void preservesAConfiguredProviderAdapter() {
        MeetingProvider adapter = mock(MeetingProvider.class);

        contextRunner
                .withBean(MeetingProvider.class, () -> adapter)
                .run(context -> {
                    assertThat(context).hasSingleBean(MeetingProvider.class);
                    assertThat(context.getBean(MeetingProvider.class)).isSameAs(adapter);
                    assertThat(context).doesNotHaveBean(DisabledMeetingProvider.class);
                });
    }
}
