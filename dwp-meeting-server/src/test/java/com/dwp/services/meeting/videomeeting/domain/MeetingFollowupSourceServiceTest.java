package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.Operation;
import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.Request;
import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.Source;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.Audience;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceReport;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ReportState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligencePayloadProtector;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Analysis;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Citation;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.CitedText;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ClimateLabel;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ClimateSignal;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ConversationClimate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MeetingFollowupSourceServiceTest {

    private static final long TENANT_ID = 7;
    private static final long ACTOR_ID = 11;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-04T05:00:01Z");

    @Mock
    private VideoMeetingRepository meetings;
    @Mock
    private VideoMeetingIntelligenceRepository intelligence;
    @Mock
    private MeetingIntelligencePayloadProtector protector;
    @Mock
    private MeetingFollowupCurrentAuthority authority;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private UUID meetingId;
    private UUID reportId;
    private IntelligenceReport report;
    private Analysis analysis;
    private MeetingFollowupSourceService service;

    @BeforeEach
    void setup() throws Exception {
        meetingId = UUID.randomUUID();
        reportId = UUID.randomUUID();
        analysis = analysis();
        byte[] payload = mapper.writeValueAsBytes(analysis);
        report = report(ReportState.PUBLISHED, Audience.MEETING_PARTICIPANTS,
                NOW.plusDays(30), HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(payload)));
        service = new MeetingFollowupSourceService(
                meetings, intelligence, protector, new MeetingContentAccessPolicy(), authority,
                mapper, Clock.fixed(Instant.from(NOW), ZoneOffset.UTC));
        lenient().when(authority.authorize(any()))
                .thenReturn(MeetingFollowupCurrentAuthority.Decision.allow());
        lenient().when(intelligence.report(TENANT_ID, meetingId, reportId))
                .thenReturn(Optional.of(report));
        lenient().when(intelligence.latestPublishedReport(TENANT_ID, meetingId, NOW))
                .thenReturn(Optional.of(report));
        lenient().when(meetings.participant(TENANT_ID, meetingId, ACTOR_ID))
                .thenReturn(Optional.of(participant(ACTOR_ID)));
        lenient().when(protector.available()).thenReturn(true);
        lenient().when(protector.ready()).thenReturn(true);
        lenient().when(protector.unprotect(TENANT_ID, reportId, "encrypted"))
                .thenAnswer(invocation -> payload.clone());
    }

    @Test
    void publishedReviewerApprovedActionItemCanBeInspectedWithoutReleasingTaskTerms() {
        var candidate = MeetingFollowupCandidateProjector.candidates(report, analysis).getFirst();
        Request request = request(Operation.READ, candidate.candidateId(), null, null);

        var response = service.resolve(request);

        assertThat(response.allowed()).isTrue();
        assertThat(response.canAssign()).isFalse();
        assertThat(response.originalAccess().name()).isEqualTo("AVAILABLE");
        assertThat(response.sourceVersion()).isEqualTo(report.version());
        assertThat(response.approvedTask()).isNull();
    }

    @Test
    void currentParticipantCanConfirmPublishedCandidateForSelfButReassignmentRemainsClosed() {
        var candidate = MeetingFollowupCandidateProjector.candidates(report, analysis).getFirst();

        var create = service.resolve(request(
                Operation.CREATE, candidate.candidateId(), null, report.version()));
        var reassign = service.resolve(request(
                Operation.REASSIGN, candidate.candidateId(), 99L, null));

        assertThat(create.allowed()).isTrue();
        assertThat(create.denialCode()).isNull();
        assertThat(create.canAssign()).isTrue();
        assertThat(create.sourceVersion()).isEqualTo(report.version());
        assertThat(create.approvedTask().assigneeUserId()).isEqualTo(ACTOR_ID);
        assertThat(create.approvedTask().title())
                .isEqualTo("Publish the governed rollout checklist.");
        assertThat(create.approvedTask().description()).isNull();
        assertThat(create.approvedTask().priority()).isEqualTo("NORMAL");
        assertThat(create.approvedTask().dueAt()).isNull();
        assertThat(reassign.allowed()).isFalse();
        assertThat(reassign.denialCode()).isEqualTo("TARGET_ELIGIBILITY_UNVERIFIED");
        assertThat(reassign.approvedTask()).isNull();
    }

    @Test
    void staleCreateVersionIsRejectedBeforeAnyCandidateContentIsOpened() {
        var candidate = MeetingFollowupCandidateProjector.candidates(report, analysis).getFirst();

        var response = service.resolve(request(
                Operation.CREATE, candidate.candidateId(), null, report.version() - 1));

        assertThat(response.allowed()).isFalse();
        assertThat(response.denialCode()).isEqualTo("SOURCE_VERSION_CONFLICT");
        assertThat(response.approvedTask()).isNull();
        assertThat(response.toString()).doesNotContain("Publish the governed rollout checklist");
    }

    @Test
    void privateDeletedExpiredAndNoLongerCurrentReportsNeverReleaseTaskTerms() {
        var candidate = MeetingFollowupCandidateProjector.candidates(report, analysis).getFirst();
        Request request = request(Operation.READ, candidate.candidateId(), null, null);

        IntelligenceReport privateReport = report(
                ReportState.APPROVED, Audience.PRIVATE_REVIEWERS, NOW.plusDays(30),
                report.payloadSha256());
        when(intelligence.report(TENANT_ID, meetingId, reportId))
                .thenReturn(Optional.of(privateReport));
        assertRedacted(service.resolve(request), "SOURCE_NOT_PUBLISHED");

        IntelligenceReport expired = report(
                ReportState.PUBLISHED, Audience.MEETING_PARTICIPANTS, NOW, report.payloadSha256());
        when(intelligence.report(TENANT_ID, meetingId, reportId))
                .thenReturn(Optional.of(expired));
        assertRedacted(service.resolve(request), "SOURCE_DELETED");

        when(intelligence.report(TENANT_ID, meetingId, reportId))
                .thenReturn(Optional.of(report));
        when(intelligence.latestPublishedReport(TENANT_ID, meetingId, NOW))
                .thenReturn(Optional.of(reportWithId(UUID.randomUUID())));
        assertRedacted(service.resolve(request), "SOURCE_SUPERSEDED");
    }

    @Test
    void missingParticipantAndUnavailableProtectionReturnNoCandidateContent() {
        var candidate = MeetingFollowupCandidateProjector.candidates(report, analysis).getFirst();
        Request request = request(Operation.READ, candidate.candidateId(), null, null);
        when(meetings.participant(TENANT_ID, meetingId, ACTOR_ID)).thenReturn(Optional.empty());
        assertRedacted(service.resolve(request), "SOURCE_ACCESS_FORBIDDEN");

        when(meetings.participant(TENANT_ID, meetingId, ACTOR_ID))
                .thenReturn(Optional.of(participant(ACTOR_ID)));
        when(protector.ready()).thenReturn(false);
        assertRedacted(service.resolve(request), "SOURCE_PROTECTION_UNAVAILABLE");
    }

    @Test
    void crossTenantSourceIdentityNeverFallsBackToCurrentTenantContent() {
        var candidate = MeetingFollowupCandidateProjector.candidates(report, analysis).getFirst();
        Request request = new Request(
                TENANT_ID + 1, ACTOR_ID,
                new Source(meetingId, reportId, candidate.candidateId()),
                Operation.CREATE, null, report.version());

        assertRedacted(service.resolve(request), "SOURCE_NOT_FOUND");
    }

    @Test
    void malformedDirectServiceRequestIsDeniedWithoutDereferencingOrContent() {
        var response = service.resolve(null);

        assertThat(response.allowed()).isFalse();
        assertThat(response.denialCode()).isEqualTo("INVALID_SOURCE_REQUEST");
        assertThat(response.tenantId()).isNull();
        assertThat(response.actorUserId()).isNull();
        assertThat(response.source()).isNull();
        assertThat(response.approvedTask()).isNull();
    }

    @Test
    void unavailableCurrentAuthorityDeniesEveryActionBeforeSourceOrPayloadAccess() {
        MeetingFollowupSourceService failClosed = new MeetingFollowupSourceService(
                meetings, intelligence, protector, new MeetingContentAccessPolicy(),
                new UnavailableMeetingFollowupCurrentAuthority(),
                mapper, Clock.fixed(Instant.from(NOW), ZoneOffset.UTC));
        UUID candidateId = UUID.randomUUID();

        for (Request request : List.of(
                request(Operation.READ, candidateId, null, null),
                request(Operation.CREATE, candidateId, null, report.version()),
                request(Operation.REASSIGN, candidateId, 99L, null))) {
            assertRedacted(failClosed.resolve(request), "AUTHORITY_UNVERIFIED");
        }

        verifyNoInteractions(meetings, intelligence, protector);
    }

    private void assertRedacted(
            com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.Response response,
            String denialCode) {
        assertThat(response.allowed()).isFalse();
        assertThat(response.denialCode()).isEqualTo(denialCode);
        assertThat(response.approvedTask()).isNull();
        assertThat(response.toString()).doesNotContain("Publish the governed rollout checklist");
    }

    private Request request(Operation action, UUID candidateId, Long target, Long version) {
        return new Request(
                TENANT_ID, ACTOR_ID, new Source(meetingId, reportId, candidateId),
                action, target, version);
    }

    private IntelligenceReport report(
            ReportState state, Audience audience, OffsetDateTime retention, String payloadSha) {
        return new IntelligenceReport(
                reportId, TENANT_ID, meetingId, UUID.randomUUID(), state, audience,
                state == ReportState.DELETED ? null : "encrypted",
                state == ReportState.DELETED ? null : payloadSha,
                "a".repeat(64), "meeting-intelligence-v1", retention, false,
                NOW.minusHours(2), 21L,
                state == ReportState.PUBLISHED ? NOW.minusHours(1) : null,
                state == ReportState.PUBLISHED ? 22L : null,
                state == ReportState.DELETED ? NOW.minusMinutes(1) : null,
                state == ReportState.DELETED ? 22L : null,
                3, 20L);
    }

    private IntelligenceReport reportWithId(UUID id) {
        return new IntelligenceReport(
                id, report.tenantId(), report.meetingId(), report.runId(), report.state(),
                report.audience(), report.encryptedPayload(), report.payloadSha256(),
                report.sourceSha256(), report.schemaVersion(), report.retentionUntil(),
                report.legalHold(), report.approvedAt(), report.approvedBy(),
                report.publishedAt(), report.publishedBy(), report.deletedAt(),
                report.deletedBy(), report.version(), report.createdBy());
    }

    private Participant participant(long userId) {
        return new Participant(
                UUID.randomUUID(), TENANT_ID, meetingId, userId, UUID.randomUUID(),
                "user@sk.com", "Meeting member", null, null,
                ParticipantRole.ATTENDEE, AttendanceState.LEFT, true,
                null, NOW.minusDays(1), null, NOW.minusHours(2), NOW.minusHours(1),
                null, null, 1);
    }

    private Analysis analysis() {
        Citation citation = new Citation("seg-1", 0, 1_000);
        CitedText cited = new CitedText("Evidence-backed summary.", List.of(citation));
        return new Analysis(
                cited, List.of(cited), List.of(cited),
                List.of(new CitedText(
                        "Publish the governed rollout checklist.", List.of(citation))),
                List.of(), List.of(),
                new ConversationClimate(
                        ClimateLabel.INSUFFICIENT_EVIDENCE,
                        List.of(ClimateSignal.LOW_TRANSCRIPT_EVIDENCE), List.of()));
    }
}
