package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceHttpProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligencePayloadProtector;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptHttpProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MeetingContentDependenciesTest {

    @Test
    void disabledBoundaryFailsClosedForEveryCapability() {
        var status = new DisabledMeetingContentDependencies().status();

        assertThat(status).isEqualTo(MeetingContentDependencies.failClosedStatus());
    }

    @Test
    void governedAdapterMakesOnlyConfiguredIntelligenceDependenciesReady() {
        Fixture fixture = readyFixture();

        var status = fixture.dependencies.status();

        assertThat(status.storageAvailable()).isTrue();
        assertThat(status.kmsAvailable()).isTrue();
        assertThat(status.languageModelAvailable()).isTrue();
        assertThat(status.auditAvailable()).isTrue();
        assertThat(status.egressAvailable()).isFalse();
        assertThat(status.speechToTextAvailable()).isFalse();
    }

    @Test
    void auditPrivilegeProbeFailureFailsClosed() {
        Fixture fixture = readyFixture();
        when(fixture.jdbc.queryForObject(anyString(), eq(Boolean.class)))
                .thenThrow(new IllegalStateException("permission denied"));

        assertThat(fixture.dependencies.status().auditAvailable()).isFalse();
    }

    @Test
    void missingInsertPrivilegeFailsAuditReadiness() {
        Fixture fixture = readyFixture();
        when(fixture.jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);

        assertThat(fixture.dependencies.status().auditAvailable()).isFalse();
    }

    @Test
    void failedKmsRuntimeProbeFailsClosed() {
        Fixture fixture = readyFixture();
        when(fixture.protector.ready()).thenReturn(false);

        assertThat(fixture.dependencies.status().kmsAvailable()).isFalse();
    }

    @Test
    void disabledTranscriptConfigurationDoesNotClaimStorage() {
        Fixture fixture = readyFixture();
        fixture.transcriptProperties.setProvider("disabled");

        assertThat(fixture.dependencies.status().storageAvailable()).isFalse();
    }

    @Test
    void unavailableTranscriptAdapterDoesNotClaimStorage() {
        Fixture fixture = readyFixture();
        when(fixture.transcriptSource.available()).thenReturn(false);

        assertThat(fixture.dependencies.status().storageAvailable()).isFalse();
    }

    @Test
    void disabledIntelligenceConfigurationDoesNotClaimLanguageModel() {
        Fixture fixture = readyFixture();
        fixture.intelligenceProperties.setProvider("disabled");

        assertThat(fixture.dependencies.status().languageModelAvailable()).isFalse();
    }

    private Fixture readyFixture() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MeetingTranscriptSource source = mock(MeetingTranscriptSource.class);
        MeetingIntelligencePayloadProtector protector =
                mock(MeetingIntelligencePayloadProtector.class);
        MeetingIntelligenceHttpProperties intelligence =
                new MeetingIntelligenceHttpProperties();
        intelligence.setProvider("http");
        MeetingTranscriptHttpProperties transcript = new MeetingTranscriptHttpProperties();
        transcript.setProvider("http");
        when(source.available()).thenReturn(true);
        when(protector.available()).thenReturn(true);
        when(protector.ready()).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        GovernedMeetingContentDependencies dependencies =
                new GovernedMeetingContentDependencies(
                        jdbc, intelligence, transcript, source, protector);
        return new Fixture(
                jdbc, source, protector, intelligence, transcript, dependencies);
    }

    private record Fixture(
            JdbcTemplate jdbc,
            MeetingTranscriptSource transcriptSource,
            MeetingIntelligencePayloadProtector protector,
            MeetingIntelligenceHttpProperties intelligenceProperties,
            MeetingTranscriptHttpProperties transcriptProperties,
            GovernedMeetingContentDependencies dependencies) {
    }
}
