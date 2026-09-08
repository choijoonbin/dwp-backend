package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordBookmarkDtos.BookmarkInput;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordBookmarkDtos.BookmarkState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.ArgumentMatchers.eq;

class MeetingRecordBookmarkPostgresTest extends MeetingWorkspacePostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Override PostgreSQLContainer<?> postgres() { return POSTGRES; }
    private MeetingRecordBookmarkService bookmarks;
    private UUID record;

    @BeforeEach
    void prepareBookmarks() {
        bookmarks = new MeetingRecordBookmarkService(new MeetingRecordBookmarkRepository(jdbc),
                meetings, new MeetingWorkspaceCommands(jdbc, mapper), audit);
        record = insertRecord(1, 3);
    }

    @Test
    void currentPageReadIsOrderedUserScopedAndDoesNotWriteDefaults() {
        UUID other = insertRecord(1, 3);
        var states = own(() -> bookmarks.read(List.of(other, record))).items();
        assertThat(states).extracting(BookmarkState::meetingId).containsExactly(other, record);
        assertThat(states).allSatisfy(state -> {
            assertThat(state.favorite()).isFalse();
            assertThat(state.version()).isZero();
            assertThat(state.updatedAt()).isNull();
        });
        assertThat(count("vm_meeting_record_bookmarks")).isZero();
        assertThat(count("vm_meeting_workspace_commands")).isZero();
        assertThat(count("sys_audit_outbox")).isZero();
    }

    @Test
    void viewOnlyActorPersistsBooleanVersionAndOtherActorNeverSeesIt() {
        participant(record, 4);
        var saved = as(1, 3, Set.of("APP.MEETINGS:VIEW"),
                () -> bookmarks.update(record, new BookmarkInput(true, 0L), "bookmark-save-001", "corr"));
        assertThat(saved.favorite()).isTrue();
        assertThat(saved.version()).isOne();
        assertThat(saved.updatedAt()).isNotNull();
        var other = as(1, 4, all(), () -> bookmarks.read(List.of(record))).items().getFirst();
        assertThat(other.favorite()).isFalse();
        assertThat(other.version()).isZero();
        assertThat(own(() -> bookmarks.read(List.of(record))).items()).containsExactly(saved);
        assertThat(count("vm_meeting_record_bookmarks")).isOne();
    }

    @Test
    void readAndWriteDenyCrossTenantUninvitedInternalAndMixedPageWithoutLeakingPartialState() {
        UUID privateRecord = insertRecord(1, 4);
        jdbc.update("UPDATE vm_meetings SET access_scope = 'INTERNAL' WHERE meeting_id = ?", privateRecord);
        assertMissing(() -> as(2, 3, all(), () -> bookmarks.read(List.of(record))));
        assertMissing(() -> as(2, 3, all(), () -> update(record, true, 0, "cross-tenant-001")));
        assertMissing(() -> own(() -> bookmarks.read(List.of(record, privateRecord))));
        assertMissing(() -> own(() -> update(privateRecord, true, 0, "uninvited-save-001")));
        assertThat(count("vm_meeting_record_bookmarks")).isZero();
        assertThat(count("vm_meeting_workspace_commands")).isZero();
    }

    @Test
    void activeMeetingsAndInvalidPageCommandsAreRejectedWithoutState() {
        jdbc.update("UPDATE vm_meetings SET lifecycle_state = 'SCHEDULED' WHERE meeting_id = ?", record);
        assertMissing(() -> own(() -> bookmarks.read(List.of(record))));
        assertMissing(() -> own(() -> update(record, true, 0, "active-save-001")));
        assertThatThrownBy(() -> own(() -> bookmarks.read(List.of(record, record))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> own(() -> bookmarks.read(List.of())))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> own(() -> bookmarks.read(java.util.Collections.nCopies(101, record))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> own(() -> bookmarks.update(record,
                new BookmarkInput(null, 0L), "null-save-001", null))).isInstanceOf(BaseException.class);
        assertThat(count("vm_meeting_record_bookmarks")).isZero();
    }

    @Test
    void idempotencyBindsTargetPayloadAndActorAndReplayReturnsCurrentStateWithoutReapplying() {
        var saved = own(() -> update(record, true, 0, "bookmark-replay-001"));
        assertThat(own(() -> update(record, true, 0, "bookmark-replay-001"))).isEqualTo(saved);
        UUID second = insertRecord(1, 3);
        assertConflict(() -> own(() -> update(second, true, 0, "bookmark-replay-001")));
        assertConflict(() -> own(() -> update(record, false, 0, "bookmark-replay-001")));
        var newer = own(() -> update(record, false, 1, "bookmark-replay-002"));
        assertThat(own(() -> update(record, true, 0, "bookmark-replay-001"))).isEqualTo(newer);
        participant(record, 4);
        assertThat(as(1, 4, all(), () -> update(record, true, 0, "bookmark-replay-001")).version()).isOne();
        assertThat(count("vm_meeting_workspace_commands")).isEqualTo(3);
        assertThat(count("sys_audit_outbox")).isEqualTo(3);
    }

    @Test
    void deniedParticipantCannotReadWriteOrReplayPreviouslySuccessfulReceipt() {
        participant(record, 4);
        as(1, 4, all(), () -> update(record, true, 0, "participant-save-001"));
        jdbc.update("UPDATE vm_meeting_participants SET attendance_state = 'DENIED' WHERE meeting_id = ? AND user_id = 4", record);
        assertMissing(() -> as(1, 4, all(), () -> bookmarks.read(List.of(record))));
        assertMissing(() -> as(1, 4, all(), () -> update(record, true, 0, "participant-save-001")));
        assertMissing(() -> as(1, 4, all(), () -> update(record, false, 0, "participant-save-001")));
        assertMissing(() -> as(1, 4, all(), () -> update(record, false, 1, "participant-save-002")));
        assertThat(as(1, 4, all(), () -> meetingService.history(0, 30, true)).total()).isZero();
        assertThat(count("vm_meeting_workspace_commands")).isOne();
        assertThat(count("sys_audit_outbox")).isOne();
    }

    @Test
    void favoriteOnlyAppliesBeforePaginationAndCountAndKeepsUnfilteredHistoryCompatible() {
        UUID second = insertRecord(1, 3);
        UUID third = insertRecord(1, 3);
        own(() -> update(record, true, 0, "filter-save-001"));
        own(() -> update(second, true, 0, "filter-save-002"));
        var firstPage = own(() -> meetings.history(1, 3, 0, 1, true));
        var secondPage = own(() -> meetings.history(1, 3, 1, 1, true));
        assertThat(firstPage.total()).isEqualTo(2);
        assertThat(secondPage.total()).isEqualTo(2);
        assertThat(firstPage.items()).hasSize(1);
        assertThat(secondPage.items()).hasSize(1);
        assertThat(firstPage.items().getFirst().meeting().meetingId())
                .isNotEqualTo(secondPage.items().getFirst().meeting().meetingId());
        assertThat(own(() -> meetings.history(1, 3, 2, 1, true)).items()).isEmpty();
        assertThat(own(() -> meetings.history(1, 3, 0, 100)).items())
                .anySatisfy(item -> assertThat(item.meeting().meetingId()).isEqualTo(third));
        assertThat(as(1, 4, all(), () -> meetingService.history(0, 30, true)).total()).isZero();
        assertThat(as(2, 3, all(), () -> meetingService.history(0, 30, true)).total()).isZero();
        own(() -> update(record, false, 1, "filter-save-003"));
        assertThat(own(() -> meetingService.history(0, 30, true)).total()).isOne();
    }

    @Test
    void firstWriteAuditFailureRollsBackStateAndReceiptAtomically() {
        doThrow(new IllegalStateException("audit unavailable")).when(audit)
                .workspaceChanged(any(), anyString(), anyString(), anyString(), any(), anyMap());
        assertThatThrownBy(() -> own(() -> update(record, true, 0, "audit-failed-001")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("vm_meeting_record_bookmarks")).isZero();
        assertThat(count("vm_meeting_workspace_commands")).isZero();
        assertThat(count("sys_audit_outbox")).isZero();
    }

    @Test
    void existingStateAuditFailureRollsBackCasAndDoesNotAdvanceReceipt() {
        var saved = own(() -> update(record, true, 0, "audit-success-001"));
        doThrow(new IllegalStateException("audit unavailable")).when(audit)
                .workspaceChanged(any(), anyString(), anyString(), anyString(), any(), anyMap());
        assertThatThrownBy(() -> own(() -> update(record, false, 1, "audit-failed-002")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(own(() -> bookmarks.read(List.of(record))).items()).containsExactly(saved);
        assertThat(count("vm_meeting_workspace_commands")).isOne();
        assertThat(count("sys_audit_outbox")).isOne();
    }

    @Test
    void concurrentCasAllowsExactlyOneNewCommandAndSameKeyRaceCommitsOnlyOnce() throws Exception {
        var differentKeys = race("race-key-one", "race-key-two");
        assertThat(differentKeys).containsExactlyInAnyOrder("SAVED:1", "RESOURCE_CONFLICT");
        UUID next = insertRecord(1, 3);
        record = next;
        assertThat(race("race-same-key", "race-same-key")).containsExactly("SAVED:1", "SAVED:1");
        assertThat(count("vm_meeting_workspace_commands")).isEqualTo(2);
        assertThat(count("sys_audit_outbox")).isEqualTo(2);
    }

    @Test
    void commandWaitingOnOwnerLockRechecksAccessAfterRevocationCommits() throws Exception {
        participant(record, 4);
        var lockingMeetings = spy(meetings);
        CountDownLatch reachesOwnerLock = new CountDownLatch(1);
        doAnswer(invocation -> {
            reachesOwnerLock.countDown();
            return invocation.callRealMethod();
        }).when(lockingMeetings).lockMeeting(eq(1L), eq(record));
        var waitingService = new MeetingRecordBookmarkService(new MeetingRecordBookmarkRepository(jdbc),
                lockingMeetings, new MeetingWorkspaceCommands(jdbc, mapper), audit);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = transaction.execute(status -> {
                meetings.lockMeeting(1, record);
                var future = executor.submit(() -> {
                    try {
                        as(1, 4, all(), () -> waitingService.update(record,
                                new BookmarkInput(true, 0L), "waiting-save-001", null));
                        return "UNEXPECTED_SUCCESS";
                    } catch (BaseException exception) { return exception.getErrorCode().name(); }
                });
                try { assertThat(reachesOwnerLock.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                jdbc.update("UPDATE vm_meeting_participants SET attendance_state = 'DENIED' WHERE meeting_id = ? AND user_id = 4", record);
                return future;
            });
            assertThat(result).isNotNull();
            assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo("ENTITY_NOT_FOUND");
        }
        assertThat(count("vm_meeting_record_bookmarks")).isZero();
        assertThat(count("vm_meeting_workspace_commands")).isZero();
        assertThat(count("sys_audit_outbox")).isZero();
    }

    @Test
    void endedRecordSupportsBookmarkAndParentDeletionCascadesOnlyItsPersonalState() {
        jdbc.update("""
                UPDATE vm_meetings SET lifecycle_state = 'ENDED', provider = 'LIVEKIT',
                    media_incarnation = gen_random_uuid(), media_access_state = 'ENDED',
                    room_name = 'ended-test-room', started_at = CURRENT_TIMESTAMP - INTERVAL '1 hour',
                    ended_at = CURRENT_TIMESTAMP, ended_by = 3 WHERE meeting_id = ?
                """, record);
        own(() -> update(record, true, 0, "ended-save-001"));
        assertThat(own(() -> bookmarks.read(List.of(record))).items().getFirst().favorite()).isTrue();
        assertThat(own(() -> meetingService.history(0, 30, true)).total()).isOne();
        jdbc.update("DELETE FROM vm_meetings WHERE tenant_id = 1 AND meeting_id = ?", record);
        assertThat(count("vm_meeting_record_bookmarks")).isZero();
        assertMissing(() -> own(() -> update(record, true, 0, "ended-save-001")));
        assertThat(count("vm_meeting_workspace_commands")).isOne();
    }

    @Test
    void successfulNoOpStillAdvancesVersionAndMetadataNeverStoresRecordContentOrTickets() {
        var noOp = own(() -> update(record, false, 0, "noop-save-001"));
        assertThat(noOp.favorite()).isFalse();
        assertThat(noOp.version()).isOne();
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_name = 'vm_meeting_record_bookmarks'
                """, String.class)).containsExactlyInAnyOrder(
                        "tenant_id", "user_id", "meeting_id", "favorite", "version", "updated_at");
        String receipt = jdbc.queryForObject("SELECT row_to_json(c)::text FROM vm_meeting_workspace_commands c", String.class);
        String auditJson = jdbc.queryForObject("SELECT row_to_json(a)::text FROM sys_audit_outbox a", String.class);
        assertThat(receipt + auditJson).doesNotContain("Private record content", "secret-transcript", "access-ticket");
        assertThat(receipt).contains("request_sha256").doesNotContain("expectedVersion");
        assertConflict(() -> own(() -> update(record, true, 0, "stale-save-001")));
    }

    @Test
    void providerSupportAndMissingAppViewCannotUsePersonalBookmarksOrFavoriteHistory() {
        assertThatThrownBy(() -> as(1, 3, Set.of("ADMIN.MEETINGS:MANAGE"),
                () -> bookmarks.read(List.of(record)))).isInstanceOf(BaseException.class);
        MeetingRequestContext.set(new MeetingRequestContext.Subject(3, 1, null, "Support",
                Set.of("PROVIDER_SUPPORT"), all(), Set.of()));
        try {
            assertThatThrownBy(() -> bookmarks.read(List.of(record))).isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> update(record, true, 0, "support-save-001")).isInstanceOf(BaseException.class);
            assertThatThrownBy(() -> meetingService.history(0, 30, true)).isInstanceOf(BaseException.class);
        } finally { MeetingRequestContext.clear(); }
        assertThat(count("vm_meeting_record_bookmarks")).isZero();
    }

    private BookmarkState update(UUID id, boolean favorite, long version, String key) {
        return bookmarks.update(id, new BookmarkInput(favorite, version), key, "bookmark-test");
    }

    private List<String> race(String firstKey, String secondKey) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = List.of(firstKey, secondKey).stream().map(key -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("race not started");
                try { return "SAVED:" + own(() -> update(record, true, 0, key)).version(); }
                catch (BaseException exception) { return exception.getErrorCode().name(); }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(results.getFirst().get(15, TimeUnit.SECONDS), results.getLast().get(15, TimeUnit.SECONDS));
        }
    }

    private UUID insertRecord(long tenant, long organizer) {
        UUID id = UUID.randomUUID();
        String code = id.toString().replace("-", "").substring(0, 12).toUpperCase()
                .replace('0', 'G').replace('1', 'H');
        jdbc.update("""
                INSERT INTO vm_meetings (meeting_id, tenant_id, title, agenda, lifecycle_state,
                    join_code, organizer_user_id, organizer_name, created_by, updated_by)
                VALUES (?, ?, 'Private record content', 'secret-transcript', 'CANCELLED', ?, ?, 'Private host', ?, ?)
                """, id, tenant, code, organizer, organizer, organizer);
        return id;
    }

    private void participant(UUID meetingId, long user) {
        jdbc.update("""
                INSERT INTO vm_meeting_participants (tenant_id, meeting_id, user_id, email_address,
                    display_name, participant_role, attendance_state, admitted_at, joined_at, left_at, created_by, updated_by)
                VALUES (1, ?, ?, 'private@example.test', 'Private participant', 'ATTENDEE', 'LEFT',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 3, 3)
                """, meetingId, user);
    }

    private void assertMissing(Runnable command) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(BaseException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND));
    }

    private void assertConflict(Runnable command) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(BaseException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }
}
