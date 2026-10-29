package com.dwp.services.platform.calendar;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarSchedulingV2Dtos.*;
import static com.dwp.services.platform.calendar.CalendarTypes.AttendeeType;
import static org.assertj.core.api.Assertions.assertThat;

class CalendarSchedulingV2HashesTest {

    private static final UUID ACTOR = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID PERSON = UUID.fromString(
            "10000000-0000-0000-0000-000000000002");

    @Test
    void canonicalCriteriaDistinguishesDelimiterValuesAndAbsentValues() {
        EvaluationRequest combinedFeature = request(new RoomCriteria(
                null, null, 2, List.of("A,B"), List.of(), ApprovalPreference.ANY));
        EvaluationRequest separateFeatures = request(new RoomCriteria(
                null, null, 2, List.of("A", "B"), List.of(), ApprovalPreference.ANY));
        EvaluationRequest absentSite = request(new RoomCriteria(
                null, null, 2, List.of(), List.of(), ApprovalPreference.ANY));
        EvaluationRequest dashSite = request(new RoomCriteria(
                "-", null, 2, List.of(), List.of(), ApprovalPreference.ANY));

        assertThat(CalendarSchedulingV2Hashes.criteria(combinedFeature))
                .isNotEqualTo(CalendarSchedulingV2Hashes.criteria(separateFeatures));
        assertThat(CalendarSchedulingV2Hashes.criteria(absentSite))
                .isNotEqualTo(CalendarSchedulingV2Hashes.criteria(dashSite));
    }

    @Test
    void participantBindingIncludesRole() {
        String optionalEvaluation = CalendarSchedulingV2Hashes.evaluationParticipants(
                ACTOR, List.of(new ParticipantRequest(
                        "p_1", PERSON, ParticipantRole.OPTIONAL)));
        String requiredEvaluation = CalendarSchedulingV2Hashes.evaluationParticipants(
                ACTOR, List.of(new ParticipantRequest(
                        "p_1", PERSON, ParticipantRole.REQUIRED)));
        String optionalEvent = CalendarSchedulingV2Hashes.eventParticipants(
                ACTOR, List.of(attendee(AttendeeType.OPTIONAL)));
        String requiredEvent = CalendarSchedulingV2Hashes.eventParticipants(
                ACTOR, List.of(attendee(AttendeeType.REQUIRED)));

        assertThat(optionalEvaluation).isEqualTo(optionalEvent);
        assertThat(requiredEvaluation).isEqualTo(requiredEvent);
        assertThat(optionalEvaluation).isNotEqualTo(requiredEvaluation);
    }

    @Test
    void selectionBindingIncludesSearchTimeZone() {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-11-02T09:00:00-05:00");
        String newYork = CalendarSchedulingV2Hashes.selection(
                startsAt, startsAt.plusMinutes(30), null, "America/New_York");
        String utc = CalendarSchedulingV2Hashes.selection(
                startsAt, startsAt.plusMinutes(30), null, "UTC");

        assertThat(newYork).isNotEqualTo(utc);
    }

    private EvaluationRequest request(RoomCriteria room) {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-11-02T09:00:00-05:00");
        return new EvaluationRequest(
                List.of(new ParticipantRequest(
                        "p_1", PERSON, ParticipantRole.REQUIRED)),
                new SearchWindow(startsAt, startsAt.plusDays(1)),
                new CandidateSlot(startsAt, startsAt.plusMinutes(30)),
                30, "America/New_York", room);
    }

    private CalendarDtos.AttendeeInput attendee(AttendeeType type) {
        return new CalendarDtos.AttendeeInput(
                8L, PERSON, "person@example.com", "Person", type);
    }
}
