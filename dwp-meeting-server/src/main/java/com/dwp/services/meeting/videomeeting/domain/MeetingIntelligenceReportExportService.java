package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingIntelligenceReportExportDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.Audience;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ContentPermission;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceReport;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ReportState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligencePayloadProtector;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Analysis;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Citation;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.CitedText;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.correlation;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHashesMatch;

/** Creates a bounded report file only after current access and audit persistence succeed. */
@Service
public class MeetingIntelligenceReportExportService {

    private static final String EXPORT_SCHEMA = "meeting-intelligence-export-v1";
    private static final int MAX_EXPORT_BYTES = 2_000_000;
    private static final List<ContentPermission> VIEW_PERMISSIONS = List.of(
            ContentPermission.VIEW, ContentPermission.REVIEW, ContentPermission.MANAGE);

    private final VideoMeetingRepository meetings;
    private final VideoMeetingIntelligenceRepository intelligence;
    private final MeetingIntelligencePayloadProtector protector;
    private final MeetingContentAccessPolicy access;
    private final VideoMeetingAuditRecorder audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public MeetingIntelligenceReportExportService(
            VideoMeetingRepository meetings,
            VideoMeetingIntelligenceRepository intelligence,
            MeetingIntelligencePayloadProtector protector,
            MeetingContentAccessPolicy access,
            VideoMeetingAuditRecorder audit,
            ObjectMapper mapper) {
        this(meetings, intelligence, protector, access, audit, mapper, Clock.systemUTC());
    }

    MeetingIntelligenceReportExportService(
            VideoMeetingRepository meetings,
            VideoMeetingIntelligenceRepository intelligence,
            MeetingIntelligencePayloadProtector protector,
            MeetingContentAccessPolicy access,
            VideoMeetingAuditRecorder audit,
            ObjectMapper mapper,
            Clock clock) {
        this.meetings = meetings;
        this.intelligence = intelligence;
        this.protector = protector;
        this.access = access;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public ReportExport export(
            UUID meetingId,
            UUID reportId,
            MeetingIntelligenceReportExportDtos.ExportCommand request,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        Participant viewer = meetings.participant(
                        subject.tenantId(), meetingId, subject.userId())
                .filter(candidate -> candidate.attendanceState() != AttendanceState.DENIED)
                .orElseThrow(this::notFound);
        IntelligenceReport report = intelligence.report(
                        subject.tenantId(), meetingId, reportId)
                .orElseThrow(this::notFound);
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean explicitGrant = intelligence.hasPermission(
                subject.tenantId(), meetingId, reportId, subject.userId(),
                VIEW_PERMISSIONS, now);
        if (report.state() != ReportState.PUBLISHED
                || report.audience() != Audience.MEETING_PARTICIPANTS
                || report.expiredAt(now)
                || report.encryptedPayload() == null
                || !access.canView(viewer, report, explicitGrant)) {
            throw notFound();
        }
        if (report.version() != request.expectedReportVersion()) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The intelligence report changed. Refresh and retry.");
        }

        ExportFormat format = format(request.format());
        Analysis analysis = open(report);
        byte[] content = format == ExportFormat.JSON
                ? json(report, analysis, now)
                : markdown(report, analysis, now).getBytes(StandardCharsets.UTF_8);
        if (content.length == 0 || content.length > MAX_EXPORT_BYTES) {
            Arrays.fill(content, (byte) 0);
            throw new BaseException(
                    ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "The intelligence report export exceeds its bounded size.");
        }
        String payloadSha256 = sha256(content);
        try {
            audit.intelligenceReportExport(
                    subject, meeting, report.reportId(), correlation(correlationId), Map.of(
                            "exportSchemaVersion", EXPORT_SCHEMA,
                            "format", format.name(),
                            "reportVersion", report.version(),
                            "reportState", report.state().name(),
                            "audience", report.audience().name(),
                            "legalHold", report.legalHold(),
                            "payloadBytes", content.length,
                            "payloadSha256", payloadSha256));
        } catch (RuntimeException exception) {
            Arrays.fill(content, (byte) 0);
            throw exception;
        }
        return new ReportExport(
                filename(report, format), format.contentType,
                report.version(), payloadSha256, content);
    }

    private Analysis open(IntelligenceReport report) {
        byte[] plaintext = null;
        try {
            if (!protector.available() || !protector.ready()) {
                throw new IllegalStateException(
                        "Meeting intelligence payload protection is unavailable.");
            }
            plaintext = protector.unprotect(
                    report.tenantId(), report.reportId(), report.encryptedPayload());
            if (!requestHashesMatch(sha256(plaintext), report.payloadSha256())) {
                throw new IllegalStateException("Report payload integrity check failed.");
            }
            return mapper.readValue(plaintext, Analysis.class);
        } catch (RuntimeException | IOException exception) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The encrypted intelligence report could not be opened.");
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private byte[] json(
            IntelligenceReport report, Analysis analysis, OffsetDateTime exportedAt) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.ofEntries(
                    Map.entry("exportSchemaVersion", EXPORT_SCHEMA),
                    Map.entry("exportedAt", exportedAt),
                    Map.entry("meetingId", report.meetingId()),
                    Map.entry("reportId", report.reportId()),
                    Map.entry("reportVersion", report.version()),
                    Map.entry("reportState", report.state().name()),
                    Map.entry("audience", report.audience().name()),
                    Map.entry("sourceSchemaVersion", report.schemaVersion()),
                    Map.entry("retentionUntil", report.retentionUntil()),
                    Map.entry("legalHold", report.legalHold()),
                    Map.entry("analysis", analysis)));
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "The intelligence report export could not be serialized.");
        }
    }

    private String markdown(
            IntelligenceReport report, Analysis analysis, OffsetDateTime exportedAt) {
        StringBuilder value = new StringBuilder()
                .append("# Meeting intelligence recap\n\n")
                .append("- Export schema: `").append(EXPORT_SCHEMA).append("`\n")
                .append("- Meeting ID: `").append(report.meetingId()).append("`\n")
                .append("- Report ID: `").append(report.reportId()).append("`\n")
                .append("- Report version: ").append(report.version()).append("\n")
                .append("- Exported at: ").append(exportedAt).append("\n")
                .append("- Retained until: ").append(report.retentionUntil()).append("\n")
                .append("- Legal hold: ").append(report.legalHold()).append("\n\n");
        section(value, "Executive summary", List.of(analysis.executiveSummary()));
        section(value, "Topics", analysis.topics());
        section(value, "Decisions", analysis.decisions());
        section(value, "Action items", analysis.actionItems());
        section(value, "Open questions", analysis.openQuestions());
        section(value, "Risks and dependencies", analysis.risks());
        value.append("## Conversation climate\n\n")
                .append("- Label: ").append(analysis.conversationClimate().label().name())
                .append("\n- Signals: ")
                .append(analysis.conversationClimate().signals().isEmpty()
                        ? "None"
                        : analysis.conversationClimate().signals().stream()
                                .map(Enum::name).sorted().reduce((left, right) -> left + ", " + right)
                                .orElse("None"))
                .append("\n");
        citations(value, analysis.conversationClimate().citations());
        return value.append("\n").toString();
    }

    private void section(StringBuilder value, String heading, List<CitedText> items) {
        value.append("## ").append(heading).append("\n\n");
        if (items.isEmpty()) {
            value.append("No evidence-supported item was identified.\n\n");
            return;
        }
        for (CitedText item : items) {
            value.append("- ").append(markdownText(item.text())).append("\n");
            citations(value, item.citations());
        }
        value.append("\n");
    }

    private void citations(StringBuilder value, List<Citation> citations) {
        if (citations.isEmpty()) return;
        value.append("  - Evidence: ")
                .append(citations.stream().map(citation -> "`"
                                + markdownText(citation.segmentId()) + "` ("
                                + citation.startMillis() + "–" + citation.endMillis() + " ms)")
                        .reduce((left, right) -> left + "; " + right).orElse(""))
                .append("\n");
    }

    private String markdownText(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private String filename(IntelligenceReport report, ExportFormat format) {
        return "dwp-meeting-recap-" + report.reportId()
                + "-v" + report.version() + "." + format.extension;
    }

    private ExportFormat format(String value) {
        try {
            return ExportFormat.valueOf(value);
        } catch (RuntimeException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The intelligence report export format is invalid.");
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private BaseException notFound() {
        return new BaseException(
                ErrorCode.ENTITY_NOT_FOUND,
                "The published intelligence report was not found.");
    }

    private enum ExportFormat {
        JSON("json", "application/json;charset=UTF-8"),
        MARKDOWN("md", "text/markdown;charset=UTF-8");

        private final String extension;
        private final String contentType;

        ExportFormat(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }
    }

    public record ReportExport(
            String filename,
            String contentType,
            long reportVersion,
            String payloadSha256,
            byte[] content) {
    }
}
