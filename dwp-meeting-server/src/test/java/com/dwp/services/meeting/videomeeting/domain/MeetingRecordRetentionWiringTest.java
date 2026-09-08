package com.dwp.services.meeting.videomeeting.domain;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MeetingRecordRetentionWiringTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MeetingRecordRetentionService.class, () -> mock(MeetingRecordRetentionService.class))
            .withUserConfiguration(MeetingRecordRetentionConfiguration.class, MeetingRecordRetentionWorker.class);

    @Test
    void defaultConfigurationDoesNotInstallDestructiveScheduledWorker() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(MeetingRecordRetentionProperties.class);
            assertThat(context.getBean(MeetingRecordRetentionProperties.class).isEnabled()).isFalse();
            assertThat(context).doesNotHaveBean(MeetingRecordRetentionWorker.class);
        });
    }

    @Test
    void onlyExplicitTrueInstallsWorkerWithoutChangingDefaultRetentionPolicy() {
        runner.withPropertyValues("dwp.meeting.record-retention.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(MeetingRecordRetentionWorker.class);
            assertThat(context.getBean(MeetingRecordRetentionProperties.class).getBatchSize()).isEqualTo(10);
        });
    }
}
