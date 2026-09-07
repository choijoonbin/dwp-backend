package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingPreparationDtos.AgendaItemInput;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingScheduleDtos.*;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AccessScope;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

class VideoMeetingSchedulePostgresTest extends MeetingWorkspacePostgresFixture {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Override
    PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    @Test
    void previewMakesNewYorkGapAndOverlapAndJanuaryMonthEndExplicit() {
        VideoMeetingScheduleService fixed = fixedSchedules("2027-01-01T00:00:00Z");

        SeriesPreviewResponse gap = own(() -> fixed.previewSeries(new SeriesPreviewRequest(
                meeting("2027-03-07T02:30:00-05:00", "America/New_York"),
                new RecurrenceRequest("WEEKLY", 1, 3))));
        assertThat(gap.hasCalendarAdjustments()).isTrue();
        assertThat(gap.occurrences()).extracting(OccurrencePreview::adjustment)
                .containsExactly("NONE", "DST_GAP_SHIFT_FORWARD", "NONE");
        assertThat(gap.occurrences().get(1).localStart()).isEqualTo("2027-03-14T02:30");
        assertThat(gap.occurrences().get(1).startsAt().toString())
                .isEqualTo("2027-03-14T03:30-04:00");

        SeriesPreviewResponse overlap = own(() -> fixed.previewSeries(new SeriesPreviewRequest(
                meeting("2027-10-31T01:30:00-04:00", "America/New_York"),
                new RecurrenceRequest("WEEKLY", 1, 2))));
        assertThat(overlap.hasCalendarAdjustments()).isTrue();
        assertThat(overlap.occurrences().get(1).adjustment())
                .isEqualTo("DST_OVERLAP_EXPLICIT_OFFSET");
        assertThat(overlap.occurrences().get(1).utcOffset()).isEqualTo("-04:00");

        SeriesPreviewResponse monthEnd = own(() -> fixed.previewSeries(new SeriesPreviewRequest(
                meeting("2027-01-31T09:00:00-05:00", "America/New_York"),
                new RecurrenceRequest("MONTHLY", 1, 3))));
        assertThat(monthEnd.occurrences()).extracting(OccurrencePreview::localStart)
                .containsExactly("2027-01-31T09:00", "2027-02-28T09:00", "2027-03-31T09:00");
        assertThat(monthEnd.occurrences()).extracting(OccurrencePreview::adjustment)
                .containsExactly("NONE", "MONTH_END_CLAMPED", "NONE");
    }

    @Test
    void previewRejectsAnOffsetThatDoesNotBelongToTheSelectedIanaZone() {
        VideoMeetingScheduleService fixed = fixedSchedules("2027-01-01T00:00:00Z");
        assertThatThrownBy(() -> own(() -> fixed.previewSeries(new SeriesPreviewRequest(
                meeting("2027-03-07T02:30:00+09:00", "America/New_York"),
                new RecurrenceRequest("WEEKLY", 1, 2)))))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void exactReviewedPreviewCreatesOneSeriesAndPayloadFreeInvitationIntents() {
        VideoMeetingScheduleService fixed = fixedSchedules("2027-01-01T00:00:00Z");
        VideoMeetingDtos.ScheduleMeetingRequest meeting =
                meeting("2027-03-07T02:30:00-05:00", "America/New_York");
        RecurrenceRequest recurrence = new RecurrenceRequest("WEEKLY", 1, 3);
        SeriesPreviewResponse preview = own(() -> fixed.previewSeries(
                new SeriesPreviewRequest(meeting, recurrence)));

        UUID first = own(() -> fixed.createSeries(
                new CreateSeriesRequest(meeting, recurrence, preview.previewFingerprint()),
                "series-reviewed-001", "schedule-test").meeting().meetingId());
        UUID replay = own(() -> fixed.createSeries(
                new CreateSeriesRequest(meeting, recurrence, preview.previewFingerprint()),
                "series-reviewed-001", "schedule-test").meeting().meetingId());

        assertThat(replay).isEqualTo(first);
        assertThat(countWhere("vm_meeting_series", "idempotency_key = 'series-reviewed-001'"))
                .isOne();
        assertThat(countWhere("vm_meeting_occurrences", "series_id = (SELECT series_id FROM vm_meeting_series WHERE idempotency_key = 'series-reviewed-001')"))
                .isEqualTo(3);
        assertThat(countWhere("vm_meeting_invitation_outbox", "meeting_id IN (SELECT meeting_id FROM vm_meeting_occurrences WHERE series_id = (SELECT series_id FROM vm_meeting_series WHERE idempotency_key = 'series-reviewed-001'))"))
                .isEqualTo(3);
        String outbox = jdbc.queryForObject("""
                SELECT string_agg(row_to_json(delivery)::text, ' ') FROM vm_meeting_invitation_outbox delivery
                 WHERE meeting_id IN (SELECT meeting_id FROM vm_meeting_occurrences)
                """, String.class);
        assertThat(outbox).doesNotContain("recipient", "email", "title", "agenda", "payload");

        assertThatThrownBy(() -> own(() -> fixed.createSeries(
                new CreateSeriesRequest(meeting, recurrence, "0".repeat(64)),
                "series-changed-preview", "schedule-test")))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void lostRescheduleResponseReplaysItsCanonicalResultBeforeCurrentVersionValidation() {
        UUID meetingId = createSingle("lost-reschedule-create");
        ScheduleStateResponse before = own(() -> schedules.read(meetingId));
        OffsetDateTime localStart = inZone(before.startsAt(), "Asia/Seoul");
        RescheduleRequest firstRequest = reviewedReschedule(schedules, meetingId,
                new RescheduleRequest(
                localStart.plusDays(1), 45, "Asia/Seoul", "THIS_ONLY", null,
                before.meetingVersion(), null));
        ScheduleStateResponse first = own(() -> schedules.reschedule(
                meetingId, firstRequest, "lost-reschedule-001", "schedule-test"));
        RescheduleRequest secondRequest = reviewedReschedule(schedules, meetingId,
                new RescheduleRequest(
                localStart.plusDays(2), 45, "Asia/Seoul", "THIS_ONLY", null,
                first.meetingVersion(), null));
        ScheduleStateResponse second = own(() -> schedules.reschedule(
                meetingId, secondRequest, "lost-reschedule-002", "schedule-test"));

        ScheduleStateResponse replay = own(() -> schedules.reschedule(
                meetingId, firstRequest, "lost-reschedule-001", "schedule-test"));
        assertThat(replay).isEqualTo(first);
        assertThat(replay.startsAt()).isNotEqualTo(second.startsAt());
        assertThat(countWhere("vm_meeting_schedule_commands", "meeting_id = '" + meetingId + "'"))
                .isEqualTo(2);
        assertThat(countWhere("vm_meeting_invitation_outbox", "meeting_id = '" + meetingId + "' AND event_type = 'MEETING_RESCHEDULED'"))
                .isEqualTo(2);
        String receipt = jdbc.queryForObject("""
                SELECT string_agg(result_projection::text, ' ') FROM vm_meeting_schedule_commands
                 WHERE meeting_id = ?
                """, String.class, meetingId);
        assertThat(receipt).doesNotContain("title", "agenda", "email", "device", "participant");
    }

    @Test
    void lostCancelResponseReplaysAfterMeetingBecameTerminal() {
        UUID meetingId = createSingle("lost-cancel-create");
        ScheduleStateResponse before = own(() -> schedules.read(meetingId));
        CancellationPreviewResponse impact = own(() -> schedules.previewCancellation(
                meetingId, new CancelPreviewRequest("THIS_ONLY", null, before.meetingVersion())));
        CancelRequest request = new CancelRequest(
                "THIS_ONLY", null, before.meetingVersion(), impact.impactFingerprint());
        ScheduleStateResponse cancelled = own(() -> schedules.cancel(
                meetingId, request, "lost-cancel-001", "schedule-test"));
        ScheduleStateResponse replay = own(() -> schedules.cancel(
                meetingId, request, "lost-cancel-001", "schedule-test"));
        assertThat(replay).isEqualTo(cancelled);
        assertThat(replay.lifecycleState()).isEqualTo("CANCELLED");
        assertThat(countWhere("vm_meeting_schedule_commands", "meeting_id = '" + meetingId + "'"))
                .isOne();
    }

    @Test
    void futureScopeChangesOnlyMutableSelectedAndFutureOccurrencesAndReconfirmsInvites() {
        VideoMeetingScheduleService fixed = fixedSchedules("2098-01-01T00:00:00Z");
        VideoMeetingDtos.ScheduleMeetingRequest input = meeting("2099-01-05T09:00:00+09:00", "Asia/Seoul");
        RecurrenceRequest recurrence = new RecurrenceRequest("WEEKLY", 1, 4);
        SeriesPreviewResponse preview = own(() -> fixed.previewSeries(new SeriesPreviewRequest(input, recurrence)));
        own(() -> fixed.createSeries(new CreateSeriesRequest(input, recurrence, preview.previewFingerprint()),
                "future-scope-series", "schedule-test"));
        List<UUID> ids = occurrenceIds("future-scope-series");
        List<OffsetDateTime> original = starts(ids);
        jdbc.update("""
                UPDATE vm_meetings SET lifecycle_state = 'ENDED', started_at = scheduled_start_at,
                    ended_at = scheduled_end_at, ended_by = 3, provider = 'LIVEKIT',
                    room_name = 'immutable-ended-occurrence', media_incarnation = ?,
                    media_access_state = 'ENDED' WHERE meeting_id = ?
                """, UUID.randomUUID(), ids.get(0));
        jdbc.update("""
                UPDATE vm_meetings SET lifecycle_state = 'CANCELLED', ended_at = CURRENT_TIMESTAMP,
                    ended_by = 3 WHERE meeting_id = ?
                """, ids.get(3));

        ScheduleStateResponse target = own(() -> fixed.read(ids.get(1)));
        RescheduleRequest changeDraft = new RescheduleRequest(
                inZone(target.startsAt(), "Asia/Seoul").plusHours(2), 60, "Asia/Seoul",
                "THIS_AND_FUTURE", target.seriesVersion(), target.meetingVersion(), null);
        SeriesPreviewResponse changePreview = own(() ->
                fixed.previewReschedule(ids.get(1), changeDraft));
        assertThat(changePreview.occurrences()).extracting(OccurrencePreview::occurrenceIndex)
                .containsExactly(2, 3);
        RescheduleRequest reviewedChange = new RescheduleRequest(
                changeDraft.startsAt(), changeDraft.durationMinutes(), changeDraft.timeZone(),
                changeDraft.scope(), changeDraft.expectedSeriesVersion(),
                changeDraft.expectedVersion(), changePreview.previewFingerprint());
        ScheduleStateResponse changed = own(() -> fixed.reschedule(ids.get(1), reviewedChange,
                "future-scope-reschedule", "schedule-test"));
        List<OffsetDateTime> after = starts(ids);

        assertThat(changed.occurrenceIndex()).isEqualTo(2);
        assertThat(after.get(0)).isEqualTo(original.get(0));
        assertThat(after.get(1)).isEqualTo(original.get(1).plusHours(2));
        assertThat(after.get(2)).isEqualTo(original.get(2).plusHours(2));
        assertThat(after.get(3)).isEqualTo(original.get(3));
        assertThat(jdbc.queryForList("""
                SELECT invitation_revision FROM vm_meeting_preparations
                 WHERE meeting_id IN (?, ?) ORDER BY meeting_id
                """, Long.class, ids.get(1), ids.get(2))).containsOnly(2L);
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT response_state FROM vm_meeting_invitation_responses
                 WHERE meeting_id IN (?, ?) AND participant_id IN (
                     SELECT participant_id FROM vm_meeting_participants
                      WHERE meeting_id IN (?, ?) AND participant_role = 'ATTENDEE')
                """, String.class, ids.get(1), ids.get(2), ids.get(1), ids.get(2)))
                .containsOnly("RECONFIRM_REQUIRED");
        assertThat(countWhere("vm_meeting_invitation_outbox", "event_type = 'MEETING_RESCHEDULED' AND meeting_id IN ('"
                + ids.get(1) + "','" + ids.get(2) + "','" + ids.get(3) + "')"))
                .isEqualTo(2);
    }

    @Test
    void reviewedFutureRescheduleRejectsAChangedCandidateSetBeforeAnyWrite() {
        VideoMeetingScheduleService fixed = fixedSchedules("2098-01-01T00:00:00Z");
        VideoMeetingDtos.ScheduleMeetingRequest input =
                meeting("2099-03-02T09:00:00+09:00", "Asia/Seoul");
        RecurrenceRequest recurrence = new RecurrenceRequest("WEEKLY", 1, 4);
        SeriesPreviewResponse preview = own(() ->
                fixed.previewSeries(new SeriesPreviewRequest(input, recurrence)));
        own(() -> fixed.createSeries(new CreateSeriesRequest(
                input, recurrence, preview.previewFingerprint()),
                "candidate-fence-series", "schedule-test"));
        List<UUID> ids = occurrenceIds("candidate-fence-series");
        List<OffsetDateTime> before = starts(ids);
        ScheduleStateResponse target = own(() -> fixed.read(ids.get(1)));
        RescheduleRequest draft = new RescheduleRequest(
                inZone(target.startsAt(), "Asia/Seoul").plusHours(1), 60, "Asia/Seoul",
                "THIS_AND_FUTURE", target.seriesVersion(), target.meetingVersion(), null);
        SeriesPreviewResponse reviewed = own(() -> fixed.previewReschedule(ids.get(1), draft));
        jdbc.update("""
                UPDATE vm_meetings SET lifecycle_state = 'CANCELLED',
                    ended_at = CURRENT_TIMESTAMP, ended_by = 3, version = version + 1
                 WHERE meeting_id = ?
                """, ids.get(2));

        RescheduleRequest command = new RescheduleRequest(
                draft.startsAt(), draft.durationMinutes(), draft.timeZone(), draft.scope(),
                draft.expectedSeriesVersion(), draft.expectedVersion(),
                reviewed.previewFingerprint());
        assertThatThrownBy(() -> own(() -> fixed.reschedule(ids.get(1), command,
                "candidate-fence-command", "schedule-test")))
                .isInstanceOf(BaseException.class);

        assertThat(starts(ids)).containsExactlyElementsOf(before);
        assertThat(countWhere("vm_meeting_schedule_commands",
                "idempotency_key = 'candidate-fence-command'"))
                .isZero();
        assertThat(countWhere("vm_meeting_invitation_outbox",
                "event_type = 'MEETING_RESCHEDULED' AND meeting_id IN ('"
                        + ids.get(1) + "','" + ids.get(2) + "','" + ids.get(3) + "')"))
                .isZero();
    }

    @Test
    void reviewedFutureCancellationExposesAffectedAndSkippedOccurrencesAndIsIdempotent() {
        VideoMeetingScheduleService fixed = fixedSchedules("2098-01-01T00:00:00Z");
        VideoMeetingDtos.ScheduleMeetingRequest input =
                meeting("2099-02-02T09:00:00+09:00", "Asia/Seoul");
        RecurrenceRequest recurrence = new RecurrenceRequest("WEEKLY", 1, 4);
        SeriesPreviewResponse preview = own(() ->
                fixed.previewSeries(new SeriesPreviewRequest(input, recurrence)));
        own(() -> fixed.createSeries(new CreateSeriesRequest(
                input, recurrence, preview.previewFingerprint()),
                "future-cancel-series", "schedule-test"));
        List<UUID> ids = occurrenceIds("future-cancel-series");
        jdbc.update("""
                UPDATE vm_meetings SET lifecycle_state = 'CANCELLED',
                    ended_at = CURRENT_TIMESTAMP, ended_by = 3
                 WHERE meeting_id = ?
                """, ids.get(3));
        ScheduleStateResponse target = own(() -> fixed.read(ids.get(1)));
        CancelPreviewRequest previewRequest = new CancelPreviewRequest(
                "THIS_AND_FUTURE", target.seriesVersion(), target.meetingVersion());
        CancellationPreviewResponse impact = own(() ->
                fixed.previewCancellation(ids.get(1), previewRequest));

        assertThat(impact.affectedOccurrenceCount()).isEqualTo(2);
        assertThat(impact.skippedImmutableOccurrenceCount()).isOne();
        CancelRequest request = new CancelRequest(
                previewRequest.scope(), previewRequest.expectedSeriesVersion(),
                previewRequest.expectedVersion(), impact.impactFingerprint());
        ScheduleStateResponse cancelled = own(() -> fixed.cancel(
                ids.get(1), request, "future-cancel-command", "schedule-test"));
        ScheduleStateResponse replay = own(() -> fixed.cancel(
                ids.get(1), request, "future-cancel-command", "schedule-test"));

        assertThat(replay).isEqualTo(cancelled);
        String selected = ids.stream().map(id -> "'" + id + "'")
                .collect(java.util.stream.Collectors.joining(","));
        assertThat(jdbc.queryForList("SELECT lifecycle_state FROM vm_meetings WHERE meeting_id IN ("
                + selected + ") ORDER BY scheduled_start_at", String.class)).containsExactly(
                                "SCHEDULED", "CANCELLED", "CANCELLED", "CANCELLED");
        assertThat(countWhere("vm_meeting_schedule_commands", "meeting_id = '" + ids.get(1)
                + "' AND operation = 'CANCEL'")).isOne();
    }

    @Test
    void scheduleAuditFailureRollsBackMeetingInviteIntentAndIdempotencyReceipt() {
        UUID meetingId = createSingle("schedule-audit-create");
        ScheduleStateResponse before = own(() -> schedules.read(meetingId));
        OffsetDateTime localStart = inZone(before.startsAt(), "Asia/Seoul");
        long eventCount = countWhere("vm_meeting_invitation_outbox", "meeting_id = '" + meetingId + "'");
        RescheduleRequest reviewed = reviewedReschedule(schedules, meetingId,
                new RescheduleRequest(localStart.plusHours(1), 45, "Asia/Seoul",
                        "THIS_ONLY", null, before.meetingVersion(), null));
        doThrow(new IllegalStateException("audit unavailable")).when(audit).meetingLifecycle(
                any(), any(), eq("meeting.rescheduled"), anyString(), anyMap());

        assertThatThrownBy(() -> own(() -> schedules.reschedule(meetingId,
                reviewed,
                "schedule-audit-failure", "schedule-test")))
                .isInstanceOf(IllegalStateException.class);
        ScheduleStateResponse after = own(() -> schedules.read(meetingId));
        assertThat(after.startsAt()).isEqualTo(before.startsAt());
        assertThat(after.meetingVersion()).isEqualTo(before.meetingVersion());
        assertThat(countWhere("vm_meeting_schedule_commands", "meeting_id = '" + meetingId + "'"))
                .isZero();
        assertThat(countWhere("vm_meeting_invitation_outbox", "meeting_id = '" + meetingId + "'"))
                .isEqualTo(eventCount);
    }

    @Test
    void attendeeAndCrossTenantCannotReadOrMutateOrganizerSchedule() {
        UUID meetingId = createSingle("schedule-acl-create");
        ScheduleStateResponse before = own(() -> schedules.read(meetingId));
        OffsetDateTime localStart = inZone(before.startsAt(), "Asia/Seoul");
        assertThatThrownBy(() -> as(1, 4, all(), () -> schedules.reschedule(meetingId,
                new RescheduleRequest(localStart.plusHours(1), 45, "Asia/Seoul",
                        "THIS_ONLY", null, before.meetingVersion(), null),
                "schedule-attendee-denied", "schedule-test")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> as(2, 3, all(), () -> schedules.read(meetingId)))
                .isInstanceOf(BaseException.class);
    }

    private VideoMeetingScheduleService fixedSchedules(String instant) {
        return new VideoMeetingScheduleService(meetingService, meetings, scheduleRepository, audit,
                Clock.fixed(Instant.parse(instant), ZoneId.of("UTC")));
    }

    private UUID createSingle(String key) {
        return own(() -> meetingService.schedule(
                meeting("2099-09-04T14:00:00+09:00", "Asia/Seoul"), key, "schedule-test")
                .meeting().meetingId());
    }

    private VideoMeetingDtos.ScheduleMeetingRequest meeting(String startsAt, String timeZone) {
        return new VideoMeetingDtos.ScheduleMeetingRequest(
                "Architecture review", "Safe schedule", "Decision and owners",
                OffsetDateTime.parse(startsAt), 45, timeZone, AccessScope.INVITED,
                true, false, false, false, false, List.of(4L), List.of(),
                List.of(new AgendaItemInput(null, "Decision", "Select option", 4L, 20)),
                null, null);
    }

    private List<UUID> occurrenceIds(String seriesKey) {
        return jdbc.queryForList("""
                SELECT occurrence.meeting_id
                  FROM vm_meeting_occurrences occurrence
                  JOIN vm_meeting_series series USING (tenant_id, series_id)
                 WHERE series.idempotency_key = ? ORDER BY occurrence.occurrence_index
                """, UUID.class, seriesKey);
    }

    private List<OffsetDateTime> starts(List<UUID> ids) {
        return ids.stream().map(id -> jdbc.queryForObject(
                "SELECT scheduled_start_at FROM vm_meetings WHERE meeting_id = ?",
                OffsetDateTime.class, id)).toList();
    }

    private RescheduleRequest reviewedReschedule(VideoMeetingScheduleService service,
            UUID meetingId, RescheduleRequest draft) {
        SeriesPreviewResponse preview = own(() -> service.previewReschedule(meetingId, draft));
        return new RescheduleRequest(draft.startsAt(), draft.durationMinutes(), draft.timeZone(),
                draft.scope(), draft.expectedSeriesVersion(), draft.expectedVersion(),
                preview.previewFingerprint());
    }

    private OffsetDateTime inZone(OffsetDateTime value, String timeZone) {
        return value.atZoneSameInstant(ZoneId.of(timeZone)).toOffsetDateTime();
    }

    private long countWhere(String table, String predicate) {
        if (!Set.of("vm_meeting_series", "vm_meeting_occurrences", "vm_meeting_invitation_outbox",
                "vm_meeting_schedule_commands").contains(table)) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + predicate, Long.class);
    }
}
