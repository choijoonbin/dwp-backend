package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.Audience;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceReport;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ReportState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AccessScope;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligencePayloadProtector;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Analysis;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Citation;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.CitedText;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ClimateLabel;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ClimateSignal;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ConversationClimate;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoMeetingIntelligenceRecapProjectionTest {

    private static final long TENANT_ID = 77L;
    private static final long USER_ID = 202L;
    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-08-29T03:00:00Z");

    @Mock private VideoMeetingRepository meetings;
    @Mock private VideoMeetingIntelligenceRepository intelligence;
    @Mock private MeetingIntelligenceProvider provider;
    @Mock private MeetingTranscriptSource transcripts;
    @Mock private MeetingIntelligencePayloadProtector protector;
    @Mock private MeetingIntelligenceOutputValidator validator;
    @Mock private MeetingIntelligenceRunTransactions runTransactions;
    @Mock private VideoMeetingAuditRecorder audit;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setContext() {
        MeetingRequestContext.set(subject());
    }

    @AfterEach
    void clearContext() {
        MeetingRequestContext.clear();
    }

    @Test
    void latestPublishedRecapUsesThePublishedReportAsItsOnlyProjection() throws Exception {
        UUID meetingId = UUID.randomUUID();
        Analysis analysis = analysis();
        byte[] plaintext = mapper.writeValueAsBytes(analysis);
        IntelligenceReport report = report(meetingId, ReportState.PUBLISHED,
                Audience.MEETING_PARTICIPANTS, sha256(plaintext));
        allowMeeting(meetingId, AttendanceState.LEFT);
        when(intelligence.latestPublishedReport(TENANT_ID, meetingId, NOW))
                .thenReturn(Optional.of(report));
        when(protector.unprotect(TENANT_ID, report.reportId(), report.encryptedPayload()))
                .thenReturn(plaintext);
        when(intelligence.reviews(TENANT_ID, meetingId, report.reportId()))
                .thenReturn(List.of());

        var response = service().latestPublishedReport(meetingId);

        assertThat(response.state()).isEqualTo("PUBLISHED");
        assertThat(response.audience()).isEqualTo("MEETING_PARTICIPANTS");
        assertThat(response.analysis()).isEqualTo(analysis);
        assertThat(response.canCurrentViewerReview()).isFalse();
    }

    @Test
    void malformedNonPublishedProjectionIsDeniedBeforePayloadDecryption() {
        UUID meetingId = UUID.randomUUID();
        IntelligenceReport draft = report(
                meetingId, ReportState.DRAFT, Audience.PRIVATE_REVIEWERS, "a".repeat(64));
        allowMeeting(meetingId, AttendanceState.LEFT);
        when(intelligence.latestPublishedReport(TENANT_ID, meetingId, NOW))
                .thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service().latestPublishedReport(meetingId))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND));
        verify(protector, never()).unprotect(any(Long.class), any(), any());
    }

    @Test
    void payloadOpenFailureDoesNotFallBackToLegacyMeetingOutcomes() {
        UUID meetingId = UUID.randomUUID();
        IntelligenceReport report = report(
                meetingId, ReportState.PUBLISHED, Audience.MEETING_PARTICIPANTS,
                "a".repeat(64));
        allowMeeting(meetingId, AttendanceState.LEFT);
        when(intelligence.latestPublishedReport(TENANT_ID, meetingId, NOW))
                .thenReturn(Optional.of(report));
        when(protector.unprotect(TENANT_ID, report.reportId(), report.encryptedPayload()))
                .thenThrow(new IllegalStateException("kms unavailable"));

        assertThatThrownBy(() -> service().latestPublishedReport(meetingId))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));
    }

    private VideoMeetingIntelligenceService service() {
        return new VideoMeetingIntelligenceService(
                meetings, intelligence, provider, transcripts, protector, validator,
                new MeetingContentAccessPolicy(), runTransactions, audit, mapper,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    private void allowMeeting(UUID meetingId, AttendanceState attendanceState) {
        Meeting meeting = meeting(meetingId);
        when(meetings.accessibleMeeting(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.of(meeting));
        when(meetings.participant(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.of(participant(meetingId, attendanceState)));
    }

    private MeetingRequestContext.Subject subject() {
        return new MeetingRequestContext.Subject(
                USER_ID, TENANT_ID, UUID.nameUUIDFromBytes("recap-viewer".getBytes()),
                "Recap viewer", Set.of("WORKSPACE_MEMBER"),
                Set.of("APP.MEETINGS:VIEW"), Set.of());
    }

    private Meeting meeting(UUID meetingId) {
        return new Meeting(
                meetingId, TENANT_ID, "Published recap", null, "Agenda",
                LifecycleState.ENDED, AccessScope.INTERNAL, "7K9M4Q2X8R6T",
                NOW.minusHours(2), NOW.minusHours(1), "Asia/Seoul",
                true, false, false, false, false, "LIVEKIT", "room",
                101L, UUID.randomUUID(), "Organizer", NOW.minusHours(2),
                NOW.minusHours(1), 101L,
                JsonNodeFactory.instance.arrayNode().addObject()
                        .put("decision", "legacy decision must not be projected"),
                JsonNodeFactory.instance.arrayNode().addObject()
                        .put("action", "legacy action must not be projected"),
                4, NOW.minusDays(1), NOW.minusHours(1));
    }

    private Participant participant(UUID meetingId, AttendanceState attendanceState) {
        return new Participant(
                UUID.randomUUID(), TENANT_ID, meetingId, USER_ID, subject().personPublicId(),
                "viewer@example.com", "Recap viewer", null, null,
                ParticipantRole.ATTENDEE, attendanceState, true,
                NOW.minusHours(2), NOW.minusHours(2), 101L,
                NOW.minusHours(2), NOW.minusHours(1), null, null, 3);
    }

    private IntelligenceReport report(
            UUID meetingId,
            ReportState state,
            Audience audience,
            String payloadSha256) {
        return new IntelligenceReport(
                UUID.randomUUID(), TENANT_ID, meetingId, UUID.randomUUID(), state, audience,
                "dwp2.encrypted", payloadSha256, "b".repeat(64),
                "meeting-intelligence-v1", NOW.plusDays(30), false,
                NOW.minusMinutes(5), 303L,
                state == ReportState.PUBLISHED ? NOW.minusMinutes(4) : null,
                state == ReportState.PUBLISHED ? 101L : null,
                null, null, 2, 101L);
    }

    private Analysis analysis() {
        Citation citation = new Citation("segment-1", 0, 1_000);
        return new Analysis(
                new CitedText("Published executive summary", List.of(citation)),
                List.of(new CitedText("Published topic", List.of(citation))),
                List.of(new CitedText("Published decision", List.of(citation))),
                List.of(new CitedText("Published action", List.of(citation))),
                List.of(), List.of(),
                new ConversationClimate(
                        ClimateLabel.ALIGNED,
                        List.of(ClimateSignal.CONSTRUCTIVE_DISAGREEMENT),
                        List.of(citation)));
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
