package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptArtifactRepository.TranscriptArtifact;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ConsentEvidence;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeetingTranscriptAccessTransactionsTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-09-04T01:00:00Z");

    @Test
    void prepareBindsTenantUserMeetingConsentRetentionVersionAndAbuseWindow() {
        Fixture fixture = fixture();

        var prepared = fixture.transactions().prepare(
                fixture.subject(), fixture.meetingId(), fixture.artifactId(), 7L, "corr-1");

        assertThat(prepared.artifact()).isSameAs(fixture.artifact());
        verify(fixture.meetings()).lockMeeting(1L, fixture.meetingId());
        verify(fixture.meetings()).participant(1L, fixture.meetingId(), 10L);
        verify(fixture.intelligence()).consentEvidence(
                1L, fixture.meetingId(), fixture.noticeId());
        verify(fixture.rateLimits()).consume(
                1L, fixture.meetingId(), 10L, NOW);
        verify(fixture.audit()).transcriptAccess(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void changedConsentSnapshotFailsClosedBeforeAuditOrExternalRead() {
        Fixture fixture = fixture();
        when(fixture.intelligence().consentEvidence(
                1L, fixture.meetingId(), fixture.noticeId()))
                .thenReturn(new ConsentEvidence(1, 1, "c".repeat(64)));

        assertThatThrownBy(() -> fixture.transactions().prepare(
                fixture.subject(), fixture.meetingId(), fixture.artifactId(), 7L, "corr-1"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("not found");
        verify(fixture.rateLimits(), never()).consume(anyLong(), any(), anyLong(), any());
        verify(fixture.audit(), never()).transcriptAccess(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void nonParticipantAndExpiredArtifactRemainIndistinguishable() {
        Fixture fixture = fixture();
        when(fixture.meetings().participant(1L, fixture.meetingId(), 10L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.transactions().prepare(
                fixture.subject(), fixture.meetingId(), fixture.artifactId(), 7L, "corr-1"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("not found");

        Fixture expired = fixture();
        when(expired.artifact().retentionUntil()).thenReturn(NOW);
        assertThatThrownBy(() -> expired.transactions().prepare(
                expired.subject(), expired.meetingId(), expired.artifactId(), 7L, "corr-1"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("not found");
    }

    private Fixture fixture() {
        UUID meetingId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        UUID noticeId = UUID.randomUUID();
        MeetingRequestContext.Subject subject = new MeetingRequestContext.Subject(
                10L, 1L, UUID.randomUUID(), "Viewer", Set.of("USER"),
                Set.of("APP.MEETINGS:VIEW"), Set.of());
        VideoMeetingRepository meetings = mock(VideoMeetingRepository.class);
        MeetingTranscriptArtifactRepository artifacts = mock(
                MeetingTranscriptArtifactRepository.class);
        VideoMeetingIntelligenceRepository intelligence = mock(
                VideoMeetingIntelligenceRepository.class);
        MeetingTranscriptAccessRepository rateLimits = mock(
                MeetingTranscriptAccessRepository.class);
        VideoMeetingAuditRecorder audit = mock(VideoMeetingAuditRecorder.class);
        Meeting meeting = mock(Meeting.class);
        Participant participant = mock(Participant.class);
        TenantPolicy policy = mock(TenantPolicy.class);
        TranscriptArtifact artifact = mock(TranscriptArtifact.class);
        when(meeting.meetingId()).thenReturn(meetingId);
        when(meeting.lifecycleState()).thenReturn(LifecycleState.ENDED);
        when(participant.admitted()).thenReturn(true);
        when(participant.participantRole()).thenReturn(ParticipantRole.ATTENDEE);
        when(meetings.lockMeeting(1L, meetingId)).thenReturn(meeting);
        when(meetings.participant(1L, meetingId, 10L)).thenReturn(Optional.of(participant));
        when(meetings.ensurePolicy(1L, 10L)).thenReturn(policy);
        when(policy.meetingsEnabled()).thenReturn(true);
        when(policy.recordingPolicy()).thenReturn("HOST_OPT_IN");
        when(artifacts.lock(1L, meetingId, artifactId)).thenReturn(Optional.of(artifact));
        when(artifact.artifactId()).thenReturn(artifactId);
        when(artifact.version()).thenReturn(7L);
        when(artifact.state()).thenReturn("AVAILABLE");
        when(artifact.retentionUntil()).thenReturn(NOW.plusDays(7));
        when(artifact.serverSideProcessingAllowed()).thenReturn(true);
        when(artifact.sourceSha256()).thenReturn("a".repeat(64));
        when(artifact.contentNoticeId()).thenReturn(noticeId);
        when(artifact.consentSnapshotSha256()).thenReturn("b".repeat(64));
        when(intelligence.consentEvidence(1L, meetingId, noticeId))
                .thenReturn(new ConsentEvidence(1, 1, "b".repeat(64)));
        when(rateLimits.consume(1L, meetingId, 10L, NOW)).thenReturn(true);
        MeetingTranscriptAccessTransactions transactions =
                new MeetingTranscriptAccessTransactions(
                        meetings, artifacts, intelligence, rateLimits, audit,
                        Clock.fixed(Instant.parse("2026-09-04T01:00:00Z"), ZoneOffset.UTC));
        return new Fixture(
                transactions, subject, meetings, intelligence, rateLimits, audit,
                artifact, meetingId, artifactId, noticeId);
    }

    private record Fixture(
            MeetingTranscriptAccessTransactions transactions,
            MeetingRequestContext.Subject subject,
            VideoMeetingRepository meetings,
            VideoMeetingIntelligenceRepository intelligence,
            MeetingTranscriptAccessRepository rateLimits,
            VideoMeetingAuditRecorder audit,
            TranscriptArtifact artifact,
            UUID meetingId,
            UUID artifactId,
            UUID noticeId) {
    }
}
