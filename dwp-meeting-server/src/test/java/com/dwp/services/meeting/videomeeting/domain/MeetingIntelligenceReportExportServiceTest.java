package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingIntelligenceReportExportDtos;
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
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Analysis;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Citation;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.CitedText;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ClimateLabel;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ClimateSignal;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ConversationClimate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingIntelligenceReportExportServiceTest {

    private static final long TENANT_ID = 77L;
    private static final long USER_ID = 202L;
    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-09-08T04:00:00Z");

    @Mock private VideoMeetingRepository meetings;
    @Mock private VideoMeetingIntelligenceRepository intelligence;
    @Mock private MeetingIntelligencePayloadProtector protector;
    @Mock private VideoMeetingAuditRecorder audit;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private UUID meetingId;
    private IntelligenceReport report;
    private byte[] plaintext;

    @BeforeEach
    void setUp() throws Exception {
        meetingId = UUID.randomUUID();
        report = report(ReportState.PUBLISHED, Audience.MEETING_PARTICIPANTS, 2);
        plaintext = mapper.writeValueAsBytes(analysis());
        report = withPayloadHash(report, sha256(plaintext));
        MeetingRequestContext.set(subject());
        when(meetings.lockMeeting(TENANT_ID, meetingId)).thenReturn(meeting());
        when(meetings.participant(TENANT_ID, meetingId, USER_ID))
                .thenReturn(Optional.of(participant(AttendanceState.LEFT)));
        when(intelligence.report(TENANT_ID, meetingId, report.reportId()))
                .thenReturn(Optional.of(report));
        when(intelligence.hasPermission(
                TENANT_ID, meetingId, report.reportId(), USER_ID, List.of(
                        VideoMeetingIntelligenceModels.ContentPermission.VIEW,
                        VideoMeetingIntelligenceModels.ContentPermission.REVIEW,
                        VideoMeetingIntelligenceModels.ContentPermission.MANAGE), NOW))
                .thenReturn(false);
    }

    @AfterEach
    void clear() {
        MeetingRequestContext.clear();
    }

    @Test
    void jsonExportRevalidatesTheExactPublishedVersionAndRecordsDigestOnlyAudit()
            throws Exception {
        readyProtector();
        when(protector.unprotect(TENANT_ID, report.reportId(), report.encryptedPayload()))
                .thenReturn(plaintext);

        var export = service().export(
                meetingId, report.reportId(), command("JSON"), "recap-export-001");

        assertThat(export.filename()).isEqualTo(
                "dwp-meeting-recap-" + report.reportId() + "-v2.json");
        assertThat(export.contentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(export.reportVersion()).isEqualTo(2);
        assertThat(export.payloadSha256()).isEqualTo(sha256(export.content()));
        var json = mapper.readTree(export.content());
        assertThat(json.path("meetingId").asText()).isEqualTo(meetingId.toString());
        assertThat(json.path("reportId").asText()).isEqualTo(report.reportId().toString());
        assertThat(json.path("reportState").asText()).isEqualTo("PUBLISHED");
        assertThat(json.path("analysis").path("executiveSummary").path("text").asText())
                .isEqualTo("Published <script>alert(1)</script> summary");
        assertThat(plaintext).containsOnly((byte) 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> evidence = ArgumentCaptor.forClass(Map.class);
        verify(audit).intelligenceReportExport(
                any(), any(), any(), any(), evidence.capture());
        assertThat(evidence.getValue())
                .containsEntry("format", "JSON")
                .containsEntry("reportVersion", 2L)
                .containsEntry("payloadSha256", export.payloadSha256())
                .doesNotContainKeys("analysis", "summary", "transcript");
    }

    @Test
    void markdownExportNeutralizesActiveMarkupAndKeepsBoundedCitations() {
        readyProtector();
        when(protector.unprotect(TENANT_ID, report.reportId(), report.encryptedPayload()))
                .thenReturn(plaintext);

        var export = service().export(
                meetingId, report.reportId(), command("MARKDOWN"), null);
        String markdown = new String(export.content(), StandardCharsets.UTF_8);

        assertThat(export.filename()).endsWith("-v2.md");
        assertThat(markdown)
                .contains("Published &lt;script&gt;alert(1)&lt;/script&gt; summary")
                .contains("`segment-1` (0–1000 ms)")
                .doesNotContain("<script>");
    }

    @Test
    void draftOrStaleVersionIsRejectedBeforeDecryptionAndAudit() {
        IntelligenceReport draft = report(
                ReportState.DRAFT, Audience.PRIVATE_REVIEWERS, 2);
        when(intelligence.report(TENANT_ID, meetingId, report.reportId()))
                .thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service().export(
                meetingId, report.reportId(), command("JSON"), null))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ENTITY_NOT_FOUND));
        verify(protector, never()).unprotect(any(Long.class), any(), any());
        verify(audit, never()).intelligenceReportExport(any(), any(), any(), any(), anyMap());
    }

    @Test
    void versionConflictIsReportedBeforePayloadAccess() {
        assertThatThrownBy(() -> service().export(
                meetingId, report.reportId(),
                new MeetingIntelligenceReportExportDtos.ExportCommand(1L, "JSON"), null))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
        verify(protector, never()).unprotect(any(Long.class), any(), any());
        verify(audit, never()).intelligenceReportExport(any(), any(), any(), any(), anyMap());
    }

    @Test
    void auditFailurePreventsAFileFromBeingReturned() {
        readyProtector();
        when(protector.unprotect(TENANT_ID, report.reportId(), report.encryptedPayload()))
                .thenReturn(plaintext);
        doThrow(new IllegalStateException("audit unavailable")).when(audit)
                .intelligenceReportExport(any(), any(), any(), any(), anyMap());

        assertThatThrownBy(() -> service().export(
                meetingId, report.reportId(), command("JSON"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
    }

    @Test
    void payloadIntegrityFailureNeverCreatesAuditEvidence() {
        readyProtector();
        when(protector.unprotect(TENANT_ID, report.reportId(), report.encryptedPayload()))
                .thenReturn("tampered".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service().export(
                meetingId, report.reportId(), command("JSON"), null))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));
        verify(audit, never()).intelligenceReportExport(any(), any(), any(), any(), anyMap());
    }

    @Test
    void unavailableProtectionFailsClosedBeforePayloadAccess() {
        when(protector.available()).thenReturn(false);

        assertThatThrownBy(() -> service().export(
                meetingId, report.reportId(), command("JSON"), null))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));
        verify(protector, never()).ready();
        verify(protector, never()).unprotect(any(Long.class), any(), any());
        verify(audit, never()).intelligenceReportExport(any(), any(), any(), any(), anyMap());
    }

    @Test
    void unreadyProtectionFailsClosedBeforePayloadAccess() {
        when(protector.available()).thenReturn(true);
        when(protector.ready()).thenReturn(false);

        assertThatThrownBy(() -> service().export(
                meetingId, report.reportId(), command("JSON"), null))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));
        verify(protector, never()).unprotect(any(Long.class), any(), any());
        verify(audit, never()).intelligenceReportExport(any(), any(), any(), any(), anyMap());
    }

    @Test
    void expiredReportIsHiddenBeforeProtectionOrAudit() {
        IntelligenceReport expired = withRetention(report, NOW, false);
        when(intelligence.report(TENANT_ID, meetingId, report.reportId()))
                .thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service().export(
                meetingId, report.reportId(), command("JSON"), null))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ENTITY_NOT_FOUND));
        verify(protector, never()).unprotect(any(Long.class), any(), any());
        verify(audit, never()).intelligenceReportExport(any(), any(), any(), any(), anyMap());
    }

    @Test
    void legalHoldKeepsAnOtherwiseExpiredPublishedReportExportable() {
        IntelligenceReport held = withRetention(report, NOW.minusDays(1), true);
        when(intelligence.report(TENANT_ID, meetingId, report.reportId()))
                .thenReturn(Optional.of(held));
        readyProtector();
        when(protector.unprotect(TENANT_ID, held.reportId(), held.encryptedPayload()))
                .thenReturn(plaintext);

        var export = service().export(
                meetingId, held.reportId(), command("MARKDOWN"), "held-export");

        assertThat(export.reportVersion()).isEqualTo(held.version());
        verify(audit).intelligenceReportExport(any(), any(), any(), any(), anyMap());
    }

    private MeetingIntelligenceReportExportService service() {
        return new MeetingIntelligenceReportExportService(
                meetings, intelligence, protector, new MeetingContentAccessPolicy(),
                audit, mapper, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    private void readyProtector() {
        when(protector.available()).thenReturn(true);
        when(protector.ready()).thenReturn(true);
    }

    private MeetingIntelligenceReportExportDtos.ExportCommand command(String format) {
        return new MeetingIntelligenceReportExportDtos.ExportCommand(2L, format);
    }

    private MeetingRequestContext.Subject subject() {
        return new MeetingRequestContext.Subject(
                USER_ID, TENANT_ID, UUID.randomUUID(), "Recap viewer",
                Set.of("WORKSPACE_MEMBER"), Set.of("APP.MEETINGS:VIEW"), Set.of());
    }

    private Meeting meeting() {
        return new Meeting(
                meetingId, TENANT_ID, "Published recap", null, "Agenda",
                LifecycleState.ENDED, AccessScope.INTERNAL, "7K9M4Q2X8R6T",
                NOW.minusHours(2), NOW.minusHours(1), "Asia/Seoul",
                true, false, false, false, false, "LIVEKIT", "room",
                101L, UUID.randomUUID(), "Organizer", NOW.minusHours(2),
                NOW.minusHours(1), 101L,
                JsonNodeFactory.instance.arrayNode(), JsonNodeFactory.instance.arrayNode(),
                4, NOW.minusDays(1), NOW.minusHours(1));
    }

    private Participant participant(AttendanceState state) {
        return new Participant(
                UUID.randomUUID(), TENANT_ID, meetingId, USER_ID, UUID.randomUUID(),
                "viewer@example.com", "Recap viewer", null, null,
                ParticipantRole.ATTENDEE, state, true,
                NOW.minusHours(2), NOW.minusHours(2), 101L,
                NOW.minusHours(2), NOW.minusHours(1), null, null, 3);
    }

    private IntelligenceReport report(
            ReportState state, Audience audience, long version) {
        return new IntelligenceReport(
                report == null ? UUID.randomUUID() : report.reportId(),
                TENANT_ID, meetingId, UUID.randomUUID(), state, audience,
                "dwp2.encrypted", "a".repeat(64), "b".repeat(64),
                "meeting-intelligence-v1", NOW.plusDays(30), false,
                NOW.minusMinutes(5), 303L,
                state == ReportState.PUBLISHED ? NOW.minusMinutes(4) : null,
                state == ReportState.PUBLISHED ? 101L : null,
                null, null, version, 101L);
    }

    private IntelligenceReport withPayloadHash(
            IntelligenceReport value, String payloadSha256) {
        return new IntelligenceReport(
                value.reportId(), value.tenantId(), value.meetingId(), value.runId(),
                value.state(), value.audience(), value.encryptedPayload(), payloadSha256,
                value.sourceSha256(), value.schemaVersion(), value.retentionUntil(),
                value.legalHold(), value.approvedAt(), value.approvedBy(),
                value.publishedAt(), value.publishedBy(), value.deletedAt(),
                value.deletedBy(), value.version(), value.createdBy());
    }

    private IntelligenceReport withRetention(
            IntelligenceReport value, OffsetDateTime retentionUntil, boolean legalHold) {
        return new IntelligenceReport(
                value.reportId(), value.tenantId(), value.meetingId(), value.runId(),
                value.state(), value.audience(), value.encryptedPayload(), value.payloadSha256(),
                value.sourceSha256(), value.schemaVersion(), retentionUntil, legalHold,
                value.approvedAt(), value.approvedBy(), value.publishedAt(), value.publishedBy(),
                value.deletedAt(), value.deletedBy(), value.version(), value.createdBy());
    }

    private Analysis analysis() {
        Citation citation = new Citation("segment-1", 0, 1_000);
        return new Analysis(
                new CitedText("Published <script>alert(1)</script> summary", List.of(citation)),
                List.of(new CitedText("Topic", List.of(citation))),
                List.of(new CitedText("Decision", List.of(citation))),
                List.of(new CitedText("Action", List.of(citation))),
                List.of(), List.of(),
                new ConversationClimate(
                        ClimateLabel.ALIGNED,
                        List.of(ClimateSignal.CONSTRUCTIVE_DISAGREEMENT),
                        List.of(citation)));
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
    }
}
