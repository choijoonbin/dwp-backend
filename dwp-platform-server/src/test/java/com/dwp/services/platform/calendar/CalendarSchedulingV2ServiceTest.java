package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarSchedulingV2Dtos.*;
import static com.dwp.services.platform.calendar.CalendarTypes.ResourceState;
import static com.dwp.services.platform.calendar.CalendarTypes.ResourceType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CalendarSchedulingV2ServiceTest {

    private static final long TENANT = 41L;
    private static final long ACTOR = 73L;
    private static final UUID ACTOR_PERSON = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-25T13:00:00Z");

    @Mock
    private CalendarSchedulingV2Repository repository;

    @Mock
    private WorkplaceRoomAccessPort roomAccess;

    private CalendarSchedulingV2Service service;

    @BeforeEach
    void setUp() {
        service = new CalendarSchedulingV2Service(
                repository, roomAccess, Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
        lenient().when(repository.identityMatches(TENANT, ACTOR, ACTOR_PERSON)).thenReturn(true);
        lenient().when(repository.policy(TENANT)).thenReturn(policy());
        lenient().when(repository.sourceVersion(TENANT)).thenReturn("source-v1");
        lenient().when(repository.workingSettings(
                eq(TENANT), any(UUID.class), any(String.class))).thenReturn(Optional.empty());
        lenient().when(repository.authorizedPeople(
                eq(TENANT), eq(ACTOR_PERSON), any(), any())).thenReturn(Set.of());
        lenient().when(repository.intervals(
                eq(TENANT), any(), any(), any())).thenReturn(List.of());
        lenient().when(repository.rooms(eq(TENANT), any(), any())).thenReturn(List.of());
        lenient().when(roomAccess.viewableResourceIds(
                eq(TENANT), eq(ACTOR), any(), any())).thenReturn(Set.of());
        lenient().when(repository.json(any())).thenReturn("{}");
    }

    @Test
    void acceptsFourteenLocalDaysAcrossBothDstTransitions() {
        ZoneId newYork = ZoneId.of("America/New_York");
        OffsetDateTime fallFrom = OffsetDateTime.parse("2026-10-25T09:00:00-04:00");
        OffsetDateTime fallTo = fallFrom.atZoneSameInstant(newYork)
                .plusDays(14).toOffsetDateTime();
        assertThat(java.time.Duration.between(fallFrom, fallTo).toHours()).isEqualTo(337);

        EvaluationResponse fall = service.evaluate(
                TENANT, ACTOR, ACTOR_PERSON, null, "en-US",
                request(fallFrom, fallTo, fallFrom, fallFrom.plusMinutes(30), List.of()));
        assertThat(fall.validUntil()).isEqualTo(
                OffsetDateTime.ofInstant(NOW.plusSeconds(25), ZoneOffset.UTC));

        OffsetDateTime springFrom = OffsetDateTime.parse("2026-03-01T09:00:00-05:00");
        OffsetDateTime springTo = springFrom.atZoneSameInstant(newYork)
                .plusDays(14).toOffsetDateTime();
        assertThat(java.time.Duration.between(springFrom, springTo).toHours()).isEqualTo(335);
        EvaluationResponse spring = service.evaluate(
                TENANT, ACTOR, ACTOR_PERSON, null, "en-US",
                request(springFrom, springTo, springFrom, springFrom.plusMinutes(30), List.of()));
        assertThat(spring.evaluationId()).isNotNull();
    }

    @Test
    void scansBeyondTheFormerFortyEightHourLimit() {
        OffsetDateTime from = OffsetDateTime.parse("2026-11-02T09:00:00-05:00");
        OffsetDateTime to = from.plusDays(7);
        when(repository.intervals(eq(TENANT), any(), eq(from), eq(to))).thenReturn(List.of(
                new CalendarSchedulingV2Repository.IntervalRow(
                        ACTOR_PERSON, from, from.plusDays(3), IntervalState.BUSY, "DWP_NATIVE")));

        EvaluationResponse response = service.evaluate(
                TENANT, ACTOR, ACTOR_PERSON, null, "en-US",
                request(from, to, from, from.plusMinutes(30), List.of()));

        assertThat(response.suggestions()).isNotEmpty();
        assertThat(response.suggestions().getFirst().startsAt()).isAfterOrEqualTo(from.plusDays(3));
    }

    @Test
    void optionalUnknownPreventsClearSuggestionAndHardPass() {
        OffsetDateTime from = OffsetDateTime.parse("2026-11-02T09:00:00-05:00");
        EvaluationRequest criteria = request(
                from, from.plusDays(1), from, from.plusMinutes(30),
                List.of(new ParticipantRequest("guest_1", null, ParticipantRole.OPTIONAL)));

        EvaluationResponse response = service.evaluate(
                TENANT, ACTOR, ACTOR_PERSON, null, "en-US", criteria);

        assertThat(response.completeness()).isEqualTo(Coverage.PARTIAL);
        assertThat(response.suggestions()).isEmpty();
        assertThat(response.sources()).singleElement().satisfies(source -> {
            assertThat(source.status()).isEqualTo(SourceStatus.PARTIAL);
            assertThat(source.lastSuccessfulSyncAt()).isNull();
        });
    }

    @Test
    void enforcesDurationExactnessAndMinutePrecision() {
        OffsetDateTime from = OffsetDateTime.parse("2026-11-02T09:00:00-05:00");
        EvaluationRequest mismatch = new EvaluationRequest(
                List.of(), new SearchWindow(from, from.plusDays(1)),
                new CandidateSlot(from, from.plusMinutes(30)), 45,
                "America/New_York", null);
        EvaluationRequest seconds = new EvaluationRequest(
                List.of(), new SearchWindow(from, from.plusDays(1)),
                new CandidateSlot(from, from.plusMinutes(30).plusSeconds(1)), 30,
                "America/New_York", null);

        assertThatThrownBy(() -> service.evaluate(
                TENANT, ACTOR, ACTOR_PERSON, null, "en-US", mismatch))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.evaluate(
                TENANT, ACTOR, ACTOR_PERSON, null, "en-US", seconds))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void roomCapacityIncludesOrganizerAndAllRequestedParticipants() {
        OffsetDateTime from = OffsetDateTime.parse("2026-11-02T09:00:00-05:00");
        UUID first = UUID.fromString("10000000-0000-0000-0000-000000000002");
        UUID second = UUID.fromString("10000000-0000-0000-0000-000000000003");
        UUID roomId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        List<ParticipantRequest> people = List.of(
                new ParticipantRequest("required_1", first, ParticipantRole.REQUIRED),
                new ParticipantRequest("optional_1", second, ParticipantRole.OPTIONAL));
        when(repository.authorizedPeople(
                TENANT, ACTOR_PERSON, null, List.of(first, second)))
                .thenReturn(Set.of(first, second));
        when(repository.rooms(TENANT, from, from.plusMinutes(30))).thenReturn(List.of(
                new CalendarSchedulingV2Repository.RoomRow(
                        roomId, "NY-201", "회의실", "Room", ResourceType.ROOM,
                        "NY", "2", 2, List.of(), "America/New_York", false,
                        ResourceState.AVAILABLE, 7, false)));
        when(roomAccess.viewableResourceIds(
                TENANT, ACTOR, null, List.of(roomId))).thenReturn(Set.of(roomId));

        EvaluationRequest request = new EvaluationRequest(
                people, new SearchWindow(from, from.plusDays(1)),
                new CandidateSlot(from, from.plusMinutes(30)), 30,
                "America/New_York",
                new RoomCriteria("NY", "2", 1, List.of(), List.of(),
                        ApprovalPreference.ANY));
        EvaluationResponse response = service.evaluate(
                TENANT, ACTOR, ACTOR_PERSON, null, "en-US", request);

        assertThat(response.rooms()).singleElement().satisfies(room -> {
            assertThat(room.eligible()).isFalse();
            assertThat(room.eligibilityReason())
                    .isEqualTo(RoomEligibilityReason.CAPACITY_TOO_SMALL);
        });
    }

    @Test
    void sourceVersionDriftMakesHardRecheckIncompleteWithoutToken() {
        OffsetDateTime from = OffsetDateTime.parse("2026-11-02T09:00:00-05:00");
        EvaluationRequest criteria = request(
                from, from.plusDays(1), from, from.plusMinutes(30), List.of());
        UUID evaluationId = UUID.randomUUID();
        CalendarSchedulingV2Repository.EvaluationEvidence evidence =
                new CalendarSchedulingV2Repository.EvaluationEvidence(
                        evaluationId, TENANT, ACTOR, ACTOR_PERSON, hex('a'), hex('b'),
                        "criteria", "[]", "[]", "source-v0", hex('c'),
                        OffsetDateTime.ofInstant(NOW.minusSeconds(5), ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(NOW.plusSeconds(20), ZoneOffset.UTC));
        when(repository.consumeChallenge(
                eq(TENANT), eq(evaluationId), eq(ACTOR), eq(ACTOR_PERSON),
                eq(hex('a')), any(), any())).thenReturn(Optional.of(evidence));
        when(repository.read("criteria", EvaluationRequest.class)).thenReturn(criteria);
        when(repository.read("[]", RoomRecommendation[].class))
                .thenReturn(new RoomRecommendation[0]);

        HardRecheckResponse response = service.hardRecheck(
                TENANT, ACTOR, ACTOR_PERSON, null, "en-US", evaluationId,
                new HardRecheckRequest("challenge", hex('a'),
                        new CandidateSlot(from, from.plusMinutes(30)), null));

        assertThat(response.status()).isEqualTo(HardRecheckStatus.INCOMPLETE);
        assertThat(response.hardRecheckToken()).isNull();
        ArgumentCaptor<CalendarSchedulingV2Repository.RecheckEvidence> saved =
                ArgumentCaptor.forClass(CalendarSchedulingV2Repository.RecheckEvidence.class);
        verify(repository).insertRecheck(saved.capture());
        assertThat(saved.getValue().resultStatus()).isEqualTo("INCOMPLETE");
    }

    private EvaluationRequest request(
            OffsetDateTime from,
            OffsetDateTime to,
            OffsetDateTime candidateFrom,
            OffsetDateTime candidateTo,
            List<ParticipantRequest> participants) {
        return new EvaluationRequest(
                participants, new SearchWindow(from, to),
                new CandidateSlot(candidateFrom, candidateTo), 30,
                "America/New_York", null);
    }

    private CalendarSchedulingV2Repository.PolicyRow policy() {
        return new CalendarSchedulingV2Repository.PolicyRow(
                LocalTime.of(9, 0), LocalTime.of(18, 0), 15, 480, 10, 3);
    }

    private String hex(char value) {
        return String.valueOf(value).repeat(64);
    }
}
