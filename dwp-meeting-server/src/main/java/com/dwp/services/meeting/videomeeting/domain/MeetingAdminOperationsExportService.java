package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.correlation;

/** Produces one bounded, aggregate-only tenant operations export and its durable audit evidence. */
final class MeetingAdminOperationsExportService {

    private static final String SCHEMA = "meeting-admin-operations-v1";
    private static final DateTimeFormatter FILENAME_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX");

    private final VideoMeetingRepository meetings;
    private final MeetingMediaProvider media;
    private final VideoMeetingAuditRecorder audit;
    private final Clock clock;

    MeetingAdminOperationsExportService(
            VideoMeetingRepository meetings,
            MeetingMediaProvider media,
            VideoMeetingAuditRecorder audit,
            Clock clock) {
        this.meetings = meetings;
        this.media = media;
        this.audit = audit;
        this.clock = clock;
    }

    MeetingAdminOperationsExport export(
            String requestedTimeZone, String correlationId) {
        var subject = MeetingWorkspacePolicy.require("ADMIN.MEETINGS", "VIEW");
        var policy = meetings.ensurePolicy(subject.tenantId(), subject.userId());
        ZoneId timeZone = timeZone(requestedTimeZone);
        OffsetDateTime observedAt = OffsetDateTime.now(clock)
                .atZoneSameInstant(timeZone).toOffsetDateTime();
        OffsetDateTime dayStart = observedAt.toLocalDate()
                .atStartOfDay(timeZone).toOffsetDateTime();
        var aggregate = meetings.adminOverview(
                subject.tenantId(), dayStart, dayStart.plusDays(1), observedAt.minusDays(7));
        var capability = media.capability();
        String csv = csv(
                observedAt, timeZone, dayStart, aggregate, capability,
                policy.participantChatAllowed());
        audit.adminOperationsExport(subject, correlation(correlationId), Map.of(
                "schemaVersion", SCHEMA,
                "observedAt", observedAt.toString(),
                "timeZone", timeZone.getId(),
                "rowCount", 1,
                "payloadSha256", VideoMeetingCommandPolicy.requestHash(csv)));
        String filename = "dwp-meeting-operations-"
                + observedAt.withOffsetSameInstant(ZoneOffset.UTC).format(FILENAME_TIME)
                + ".csv";
        return new MeetingAdminOperationsExport(
                filename, csv.getBytes(StandardCharsets.UTF_8));
    }

    private String csv(
            OffsetDateTime observedAt,
            ZoneId timeZone,
            OffsetDateTime dayStart,
            VideoMeetingQueryModels.AdminOverviewData aggregate,
            MeetingMediaProvider.Capability capability,
            boolean participantChatAllowed) {
        String header = String.join(",", List.of(
                "schemaVersion", "observedAt", "timeZone", "dayStart", "dayEnd",
                "liveMeetings", "scheduledToday", "waitingParticipants",
                "meetingsLastSevenDays", "failedJoinAttempts", "qualityStatus",
                "averageQualityScore", "videoAvailable", "screenShareAvailable",
                "participantChatAllowed"));
        String row = List.of(
                        SCHEMA, observedAt.toString(), timeZone.getId(), dayStart.toString(),
                        dayStart.plusDays(1).toString(),
                        Integer.toString(aggregate.liveMeetings()),
                        Integer.toString(aggregate.scheduledToday()),
                        Integer.toString(aggregate.waitingParticipants()),
                        Integer.toString(aggregate.meetingsLastSevenDays()),
                        Integer.toString(aggregate.failedJoinAttempts()),
                        "NOT_MEASURED", "", Boolean.toString(capability.video()),
                        Boolean.toString(capability.screenShare()),
                        Boolean.toString(participantChatAllowed))
                .stream().map(MeetingAdminOperationsExportService::csvCell)
                .collect(Collectors.joining(","));
        return header + "\r\n" + row + "\r\n";
    }

    private ZoneId timeZone(String value) {
        String normalized = value == null || value.isBlank() ? "UTC" : value.trim();
        try {
            return ZoneId.of(normalized);
        } catch (java.time.DateTimeException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The time zone is invalid.");
        }
    }

    private static String csvCell(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
