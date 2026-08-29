package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingAdminIntelligenceDtos;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoMeetingAdminIntelligenceReadinessServiceTest {

    private static final long TENANT_ID = 77L;
    private static final long USER_ID = 101L;
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 29, 3, 30, 0, 0, ZoneOffset.UTC);

    @Mock
    private VideoMeetingRepository meetings;
    @Mock
    private MeetingMediaProvider media;
    @Mock
    private MeetingContentDependencies dependencies;
    @Mock
    private MeetingIntelligenceProvider intelligence;
    @Mock
    private MeetingIntelligenceRetentionService retention;
    @Mock
    private JdbcTemplate jdbc;

    @AfterEach
    void clearContext() {
        MeetingRequestContext.clear();
    }

    @Test
    void readinessFailsClosedWhenRuntimeProbesCannotBeTrusted() {
        MeetingRequestContext.set(subject());
        when(meetings.policy(TENANT_ID)).thenReturn(java.util.Optional.of(policy("NEVER")));
        when(media.capability()).thenThrow(new IllegalStateException("provider down"));
        when(dependencies.status()).thenThrow(new IllegalStateException("probe down"));
        when(intelligence.capability(any())).thenThrow(new IllegalStateException("agent down"));
        when(retention.ready()).thenThrow(new IllegalStateException("worker stale"));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class)))
                .thenThrow(new IllegalStateException("database probe denied"));

        VideoMeetingAdminIntelligenceDtos.ReadinessResponse response = service().readiness();

        assertThat(response.observedAt()).isEqualTo(NOW);
        assertThat(response.providerCode()).isEqualTo("disabled");
        assertThat(response.capabilities())
                .allSatisfy((key, signal) -> {
                    assertThat(signal.state()).isEqualTo("BLOCKED");
                    assertThat(signal.reason()).isEqualTo("POLICY_NEVER");
                });
        assertThat(response.dependencies())
                .allSatisfy((key, signal) ->
                        assertThat(signal.state()).isEqualTo("CONNECTION_REQUIRED"));
        assertThat(response.governance().get("humanReview").state())
                .isEqualTo("NOT_VERIFIED");
        assertThat(response.governance().get("deletionEvidence").state())
                .isEqualTo("NOT_VERIFIED");
        assertThat(response.retention().intelligenceWorkerReady()).isFalse();
        assertThat(response.retention().signals().get("intelligenceReports").state())
                .isEqualTo("CONNECTION_REQUIRED");
        assertThat(response.retention().signals().get("chat").state())
                .isEqualTo("NOT_VERIFIED");
    }

    @Test
    void readinessBecomesReadyOnlyWhenLiveDependenciesAndDurableEvidencePass() {
        MeetingRequestContext.set(subject());
        when(meetings.policy(TENANT_ID))
                .thenReturn(java.util.Optional.of(policy("HOST_OPT_IN")));
        when(media.capability()).thenReturn(new MeetingMediaProvider.Capability(
                true, "LIVEKIT", null, true, true, true, true, 300));
        when(media.operationallyReady()).thenReturn(true);
        when(dependencies.status()).thenReturn(new MeetingContentDependencies.Status(
                true, true, true, true, true, true));
        when(intelligence.capability(any())).thenReturn(
                new MeetingIntelligenceProvider.Capability(
                        true, "AZURE_OPENAI", "approved-model", "korea-central",
                        true, true, List.of("meeting-intelligence-v1")));
        when(retention.ready()).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        when(jdbc.queryForObject(
                anyString(), eq(Boolean.class), any(), any(), any(), any()))
                .thenReturn(true);

        VideoMeetingAdminIntelligenceDtos.ReadinessResponse response = service().readiness();

        assertThat(response.recordingPolicy()).isEqualTo("HOST_OPT_IN");
        assertThat(response.providerCode()).isEqualTo("AZURE_OPENAI");
        assertThat(response.processingRegion()).isEqualTo("korea-central");
        assertThat(response.capabilities().values())
                .allSatisfy(signal -> assertThat(signal.state()).isEqualTo("READY"));
        assertThat(response.dependencies().values())
                .allSatisfy(signal -> assertThat(signal.state()).isEqualTo("READY"));
        assertThat(response.governance().get("humanReview").state()).isEqualTo("READY");
        assertThat(response.governance().get("explicitPublish").state()).isEqualTo("READY");
        assertThat(response.governance().get("adminContentAccess").state())
                .isEqualTo("READY");
        assertThat(response.governance().get("legalHold").state())
                .isEqualTo("NOT_VERIFIED");
        assertThat(response.governance().get("deletionEvidence").state())
                .isEqualTo("NOT_VERIFIED");
        assertThat(response.governance().get("deletionEvidence").reason())
                .isEqualTo("COMPLETE_DELETION_EVIDENCE_NOT_VERIFIED");
        assertThat(response.retention().signals().get("intelligenceReports").state())
                .isEqualTo("READY");
        assertThat(response.retention().signals().get("meetingRecords").state())
                .isEqualTo("NOT_VERIFIED");
        assertThat(response.retention().signals().get("artifacts").state())
                .isEqualTo("NOT_VERIFIED");
        assertThat(response.retention().signals().get("chat").state())
                .isEqualTo("NOT_VERIFIED");
    }

    @Test
    void providerSelfAssertionsDoNotMakeAiReadyWithoutBothSafetyAttestations() {
        MeetingRequestContext.set(subject());
        when(meetings.policy(TENANT_ID))
                .thenReturn(java.util.Optional.of(policy("HOST_OPT_IN")));
        when(media.capability()).thenReturn(new MeetingMediaProvider.Capability(
                true, "LIVEKIT", null, true, true, true, true, 300));
        when(media.operationallyReady()).thenReturn(true);
        when(dependencies.status()).thenReturn(new MeetingContentDependencies.Status(
                true, true, true, true, true, true));
        when(intelligence.capability(any())).thenReturn(
                new MeetingIntelligenceProvider.Capability(
                        true, "MANAGED", "unverified-model", "korea-central",
                        true, false, List.of("meeting-intelligence-v1")));
        when(retention.ready()).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);

        VideoMeetingAdminIntelligenceDtos.ReadinessResponse response = service().readiness();

        assertThat(response.providerCode()).isEqualTo("disabled");
        assertThat(response.dependencies().get("llm").state())
                .isEqualTo("CONNECTION_REQUIRED");
        assertThat(response.capabilities().get("recording").state()).isEqualTo("READY");
        assertThat(response.capabilities().get("transcript").state()).isEqualTo("READY");
        assertThat(response.capabilities().get("aiNotes").state())
                .isEqualTo("CONNECTION_REQUIRED");
    }

    @Test
    void attestedConfigurationDoesNotClaimLlmReadyWithoutRecentSuccessfulExecution() {
        MeetingRequestContext.set(subject());
        when(meetings.policy(TENANT_ID))
                .thenReturn(java.util.Optional.of(policy("HOST_OPT_IN")));
        when(media.capability()).thenReturn(new MeetingMediaProvider.Capability(
                true, "LIVEKIT", null, true, true, true, true, 300));
        when(media.operationallyReady()).thenReturn(true);
        when(dependencies.status()).thenReturn(new MeetingContentDependencies.Status(
                true, true, true, true, true, true));
        when(intelligence.capability(any())).thenReturn(
                new MeetingIntelligenceProvider.Capability(
                        true, "AZURE_OPENAI", "approved-model", "korea-central",
                        true, true, List.of("meeting-intelligence-v1")));
        when(retention.ready()).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        when(jdbc.queryForObject(
                anyString(), eq(Boolean.class), any(), any(), any(), any()))
                .thenReturn(false);

        VideoMeetingAdminIntelligenceDtos.ReadinessResponse response = service().readiness();

        assertThat(response.dependencies().get("llm").state())
                .isEqualTo("CONNECTION_REQUIRED");
        assertThat(response.dependencies().get("llm").reason())
                .isEqualTo("LLM_OPERATIONAL_EVIDENCE_NOT_READY");
        assertThat(response.capabilities().get("aiNotes").state())
                .isEqualTo("CONNECTION_REQUIRED");
    }

    private VideoMeetingAdminIntelligenceReadinessService service() {
        return new VideoMeetingAdminIntelligenceReadinessService(
                meetings, media, dependencies, intelligence, retention, jdbc,
                Clock.fixed(Instant.from(NOW), ZoneOffset.UTC));
    }

    private MeetingRequestContext.Subject subject() {
        return new MeetingRequestContext.Subject(
                USER_ID, TENANT_ID, UUID.randomUUID(), "Admin",
                Set.of("MEETING_ADMIN"), Set.of("ADMIN.MEETINGS:VIEW"), Set.of());
    }

    private TenantPolicy policy(String recordingPolicy) {
        return new TenantPolicy(
                TENANT_ID, true, true, false, true, true, true,
                "REQUEST_ONLY", recordingPolicy, false, true,
                100, 1095, 365, 90, 4);
    }
}
