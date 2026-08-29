package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MeetingRuntimeWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(VideoMeetingRepository.class,
                    () -> mock(VideoMeetingRepository.class))
            .withBean(MeetingMediaProvider.class,
                    () -> mock(MeetingMediaProvider.class))
            .withBean(MeetingContentDependencies.class,
                    () -> mock(MeetingContentDependencies.class))
            .withBean(MeetingIntelligenceProvider.class,
                    () -> mock(MeetingIntelligenceProvider.class))
            .withBean(MeetingIntelligenceRetentionService.class,
                    () -> mock(MeetingIntelligenceRetentionService.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(VideoMeetingLifecycleOperationRepository.class,
                    () -> mock(VideoMeetingLifecycleOperationRepository.class))
            .withBean(MeetingMediaProperties.class)
            .withBean(MeetingLifecycleRecoveryProperties.class)
            .withUserConfiguration(
                    VideoMeetingAdminIntelligenceReadinessService.class,
                    VideoMeetingLifecycleRecoveryTransactions.class);

    @Test
    void productionConstructorsWireWithoutAClockBean() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(
                    VideoMeetingAdminIntelligenceReadinessService.class);
            assertThat(context).hasSingleBean(
                    VideoMeetingLifecycleRecoveryTransactions.class);
        });
    }
}
