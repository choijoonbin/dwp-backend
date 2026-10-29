package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.presence.MessagingPresenceService;
import com.dwp.services.messaging.realtime.MessagingEventRecorder;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessagingPresenceProjectionTest {

    @Mock
    private MessagingQueryRepository queries;
    @Mock
    private MessagingCommandRepository commands;
    @Mock
    private MessagingMessageQueryRepository messageQueries;
    @Mock
    private MessagingInteractionCommandRepository interactions;
    @Mock
    private MessagingEventRecorder events;
    @Mock
    private MessagingPresenceService presence;

    private MessagingService service;

    @BeforeEach
    void setUp() {
        service = new MessagingService(
                queries, commands, messageQueries, interactions, events, presence);
        MessagingRequestContext.set(new MessagingRequestContext.Subject(
                100,
                1,
                UUID.randomUUID(),
                "Viewer",
                Set.of("WORKSPACE_MEMBER"),
                Set.of("APP.MESSAGING:VIEW"),
                Set.of()));
    }

    @AfterEach
    void clearContext() {
        MessagingRequestContext.clear();
    }

    @Test
    void homeListsOnlyPeopleWithAnAuthoritativeLiveLease() {
        MessagingPresenceService.PresenceSnapshot snapshot =
                MessagingPresenceService.PresenceSnapshot.authoritative(Set.of(100L, 200L, 300L));
        List<MessagingDtos.PersonSummary> directory = List.of(
                person(200, "UNKNOWN"), person(300, "AWAY"));
        List<MessagingDtos.PersonSummary> resolved = List.of(
                person(200, "AVAILABLE"), person(300, "AVAILABLE"));
        MessagingDtos.PresenceMetadata metadata = new MessagingDtos.PresenceMetadata(
                "LIVE", OffsetDateTime.parse("2026-10-27T06:00:00Z"), 75, 20);
        when(presence.availableCandidates(1, 32)).thenReturn(snapshot);
        when(presence.metadata(snapshot)).thenReturn(metadata);
        when(queries.peopleByUserIds(1, 100, snapshot.availableUserIds(), 8))
                .thenReturn(directory);
        when(presence.resolvePeople(snapshot, directory)).thenReturn(resolved);
        when(queries.conversations(1, 100, "ALL", "", 0, 8)).thenReturn(List.of());
        when(queries.conversations(1, 100, "SPACES", "", 0, 6)).thenReturn(List.of());

        MessagingDtos.HomeResponse response = service.home();

        assertThat(response.people()).isEqualTo(resolved);
        assertThat(response.presence()).isEqualTo(metadata);
        verify(queries).peopleByUserIds(1, 100, Set.of(100L, 200L, 300L), 8);
        verify(queries, never()).people(1, 100, "", 8);
    }

    @Test
    void homeReturnsNoAvailablePeopleWhenPresenceAuthorityIsUnavailable() {
        MessagingPresenceService.PresenceSnapshot snapshot =
                MessagingPresenceService.PresenceSnapshot.unavailable();
        MessagingDtos.PresenceMetadata metadata = new MessagingDtos.PresenceMetadata(
                "UNAVAILABLE", OffsetDateTime.parse("2026-10-27T06:00:00Z"), 75, 20);
        when(presence.availableCandidates(1, 32)).thenReturn(snapshot);
        when(presence.metadata(snapshot)).thenReturn(metadata);
        when(queries.conversations(1, 100, "ALL", "", 0, 8)).thenReturn(List.of());
        when(queries.conversations(1, 100, "SPACES", "", 0, 6)).thenReturn(List.of());

        MessagingDtos.HomeResponse response = service.home();

        assertThat(response.people()).isEmpty();
        assertThat(response.presence().authority()).isEqualTo("UNAVAILABLE");
        verify(queries, never()).peopleByUserIds(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void peopleEndpointOverlaysEveryDirectoryResultFromOneSnapshot() {
        List<MessagingDtos.PersonSummary> directory = List.of(
                person(200, "AWAY"), person(300, "AVAILABLE"));
        List<MessagingDtos.PersonSummary> resolved = List.of(
                person(200, "AVAILABLE"), person(300, "OFFLINE"));
        when(queries.people(1, 100, "kim", 50)).thenReturn(directory);
        when(presence.resolvePeople(1, directory)).thenReturn(resolved);

        assertThat(service.people(" kim ", 999)).isEqualTo(resolved);
        verify(presence).resolvePeople(1, directory);
    }

    @Test
    void peoplePageUsesTenantKeysetFiltersAndFiltersOnlyAuthoritativePresence() {
        List<MessagingDtos.PersonSummary> directory = List.of(
                person(200, "UNKNOWN"), person(300, "UNKNOWN"));
        List<MessagingDtos.PersonSummary> resolved = List.of(
                person(200, "OFFLINE"), person(300, "AVAILABLE"));
        MessagingQueryRepository.PeoplePage directoryPage =
                new MessagingQueryRepository.PeoplePage(directory, 4, true);
        MessagingPresenceService.PresenceSnapshot snapshot =
                MessagingPresenceService.PresenceSnapshot.authoritative(Set.of(300L));
        MessagingDtos.PresenceMetadata metadata = new MessagingDtos.PresenceMetadata(
                "LIVE", OffsetDateTime.parse("2026-10-27T06:00:00Z"), 75, 20);
        when(queries.peoplePage(1, 100, "kim", "risk", "analyst", null, 0, 2))
                .thenReturn(directoryPage);
        when(presence.snapshot(1, List.of(200L, 300L))).thenReturn(snapshot);
        when(presence.resolvePeople(snapshot, directory)).thenReturn(resolved);
        when(presence.metadata(snapshot)).thenReturn(metadata);

        MessagingDtos.PersonPage response = service.peoplePage(
                " kim ", " Risk ", " Analyst ", "available", null, 0, 2);

        assertThat(response.items()).extracting(MessagingDtos.PersonSummary::userId)
                .containsExactly(300L);
        assertThat(response.total()).isEqualTo(4);
        assertThat(response.hasMore()).isTrue();
        assertThat(response.nextOffset()).isEqualTo(2);
        assertThat(response.presence()).isEqualTo(metadata);
        assertThat(MessagingPeopleCursor.decode(
                response.nextCursor(), 1, 100, "kim", "risk", "analyst", "AVAILABLE"))
                .isEqualTo(new MessagingPeopleCursor.Cursor(
                        "Person 300", directory.get(1).personPublicId()));
    }

    @Test
    void peoplePageFailsClosedForOfflineFilterWhenPresenceAuthorityIsUnavailable() {
        List<MessagingDtos.PersonSummary> directory = List.of(person(200, "OFFLINE"));
        MessagingQueryRepository.PeoplePage directoryPage =
                new MessagingQueryRepository.PeoplePage(directory, 1, false);
        MessagingPresenceService.PresenceSnapshot snapshot =
                MessagingPresenceService.PresenceSnapshot.unavailable();
        List<MessagingDtos.PersonSummary> unknown = List.of(person(200, "UNKNOWN"));
        MessagingDtos.PresenceMetadata metadata = new MessagingDtos.PresenceMetadata(
                "UNAVAILABLE", OffsetDateTime.parse("2026-10-27T06:00:00Z"), 75, 20);
        when(queries.peoplePage(1, 100, "", "", "", null, 0, 30))
                .thenReturn(directoryPage);
        when(presence.snapshot(1, List.of(200L))).thenReturn(snapshot);
        when(presence.resolvePeople(snapshot, directory)).thenReturn(unknown);
        when(presence.metadata(snapshot)).thenReturn(metadata);

        MessagingDtos.PersonPage response = service.peoplePage(
                "", "", "", "OFFLINE", "", 0, 30);

        assertThat(response.items()).isEmpty();
        assertThat(response.total()).isOne();
        assertThat(response.presence().authority()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void peoplePageRejectsUntrustedPagingAndPresenceInputs() {
        assertThatThrownBy(() -> service.peoplePage(
                "", "", "", "BUSY", null, 0, 30))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        assertThatThrownBy(() -> service.peoplePage(
                "", "", "", "ALL", "not-a-cursor", 0, 30))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        assertThatThrownBy(() -> service.peoplePage(
                "", "", "", "ALL", null, -1, 30))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void peopleCursorRoundTripsUnicodeAndBindsActorTenantAndFilters() {
        UUID personPublicId = UUID.randomUUID();
        MessagingDtos.PersonSummary person = new MessagingDtos.PersonSummary(
                902,
                personPublicId,
                "jun@example.com",
                "최준빈 책임",
                "Engineer",
                "DWP",
                "UNKNOWN");

        String cursor = MessagingPeopleCursor.encode(
                person, 1, 100, "jun", "dwp", "engineer", "ALL");

        assertThat(MessagingPeopleCursor.decode(
                cursor, 1, 100, "jun", "dwp", "engineer", "ALL"))
                .isEqualTo(new MessagingPeopleCursor.Cursor("최준빈 책임", personPublicId));
        assertThatThrownBy(() -> MessagingPeopleCursor.decode(
                cursor, 2, 100, "jun", "dwp", "engineer", "ALL"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> MessagingPeopleCursor.decode(
                cursor, 1, 101, "jun", "dwp", "engineer", "ALL"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> MessagingPeopleCursor.decode(
                cursor, 1, 100, "other", "dwp", "engineer", "ALL"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> MessagingPeopleCursor.decode(
                "QQ.0902", 1, 100, "jun", "dwp", "engineer", "ALL"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void peopleCursorTakesPriorityOverTheOffsetFallback() {
        MessagingDtos.PersonSummary after = person(200, "UNKNOWN");
        String cursor = MessagingPeopleCursor.encode(after, 1, 100, "", "", "", "ALL");
        MessagingPeopleCursor.Cursor decoded = MessagingPeopleCursor.decode(
                cursor, 1, 100, "", "", "", "ALL");
        MessagingQueryRepository.PeoplePage directoryPage =
                new MessagingQueryRepository.PeoplePage(List.of(), 0, false);
        MessagingPresenceService.PresenceSnapshot snapshot =
                MessagingPresenceService.PresenceSnapshot.authoritative(Set.of());
        when(queries.peoplePage(1, 100, "", "", "", decoded, 0, 30))
                .thenReturn(directoryPage);
        when(presence.snapshot(1, List.of())).thenReturn(snapshot);
        when(presence.resolvePeople(snapshot, List.of())).thenReturn(List.of());
        when(presence.metadata(snapshot)).thenReturn(new MessagingDtos.PresenceMetadata(
                "LIVE", OffsetDateTime.parse("2026-10-27T06:00:00Z"), 75, 20));

        MessagingDtos.PersonPage response = service.peoplePage(
                "", "", "", "ALL", cursor, -1, 30);

        assertThat(response.items()).isEmpty();
        assertThat(response.nextOffset()).isNull();
    }

    @Test
    void conversationMembersUseTheRuntimeSnapshot() {
        UUID conversationId = UUID.randomUUID();
        MessagingDtos.ConversationSummary conversation = conversation(conversationId);
        List<MessagingDtos.MemberSummary> directory = List.of(member(100, "AVAILABLE"));
        List<MessagingDtos.MemberSummary> resolved = List.of(member(100, "OFFLINE"));
        when(queries.conversation(1, 100, conversationId)).thenReturn(java.util.Optional.of(conversation));
        when(queries.members(1, conversationId, 100)).thenReturn(directory);
        when(presence.resolveMembers(1, directory)).thenReturn(resolved);
        when(queries.messages(1, conversationId, 100, 80)).thenReturn(List.of());

        MessagingDtos.ConversationDetail response = service.conversation(conversationId);

        assertThat(response.members()).isEqualTo(resolved);
        verify(presence).resolveMembers(1, directory);
    }

    private MessagingDtos.PersonSummary person(long userId, String state) {
        return new MessagingDtos.PersonSummary(
                userId,
                UUID.randomUUID(),
                "person" + userId + "@example.com",
                "Person " + userId,
                "Engineer",
                "DWP",
                state);
    }

    private MessagingDtos.MemberSummary member(long userId, String state) {
        return new MessagingDtos.MemberSummary(
                userId,
                UUID.randomUUID(),
                "Person " + userId,
                "person" + userId + "@example.com",
                "Engineer",
                "DWP",
                state,
                "MEMBER",
                "DIRECT",
                "DEFAULT",
                false,
                false,
                null,
                0,
                null,
                "PRIVATE");
    }

    private MessagingDtos.ConversationSummary conversation(UUID conversationId) {
        return new MessagingDtos.ConversationSummary(
                conversationId,
                "channel:test",
                "CHANNEL",
                "Test",
                null,
                "PRIVATE",
                "INTERNAL",
                null,
                null,
                "ACTIVE",
                2,
                0,
                false,
                false,
                null,
                OffsetDateTime.now(),
                0);
    }
}
