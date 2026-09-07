package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.*;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

class MeetingPersonalRoomPostgresTest extends MeetingWorkspacePostgresFixture {
    @org.testcontainers.junit.jupiter.Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRES =
            new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");
    @Override org.testcontainers.containers.PostgreSQLContainer<?> postgres() { return POSTGRES; }
    @Test
    void provisionsSelfOnlyAndRotationKeepsAliasButRejectsOldInvitation() {
        assertThat(own(() -> rooms.get())).isNull();
        var room = own(() -> rooms.create(new RoomCreate("My office"), "personal-create-1", "corr"));
        assertThat(room.opaqueAlias()).matches("[a-f0-9]{32}");
        assertThat(as(1, 4, all(), () -> rooms.get())).isNull();
        assertThatThrownBy(() -> as(2, 3, all(), () -> rooms.resolve(room.opaqueAlias(), 1)))
                .isInstanceOf(BaseException.class);
        var rotated = own(() -> rooms.rotate(new VersionCommand(0L), "personal-rotate-1", "corr"));
        assertThat(rotated.opaqueAlias()).isEqualTo(room.opaqueAlias());
        assertThat(rotated.invitationRevision()).isEqualTo(2);
        assertThatThrownBy(() -> as(1, 4, all(), () -> rooms.resolve(room.opaqueAlias(), 1)))
                .isInstanceOf(BaseException.class);
        assertThat(as(1, 4, all(), () -> rooms.resolve(room.opaqueAlias(), 2)).sessionAvailable()).isFalse();
        assertThat(own(() -> rooms.rotate(new VersionCommand(0L), "personal-rotate-1", "corr")))
                .isEqualTo(rotated);
        assertThatThrownBy(() -> own(() -> rooms.update(new RoomUpdate("Stale", 0L), "personal-update-1", "corr")))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void sameKeySessionRaceCreatesOneLobbyAndNeverCallsMediaProvider() throws Exception {
        var room = own(() -> rooms.create(new RoomCreate("My office"), "personal-create-1", "corr"));
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        long before = count("vm_meetings");
        try {
            var first = executor.submit(() -> {
                ready.countDown(); start.await();
                return own(() -> rooms.createSession(new RoomSessionCommand(0L, 1), "personal-session-1", "corr"));
            });
            var second = executor.submit(() -> {
                ready.countDown(); start.await();
                return own(() -> rooms.createSession(new RoomSessionCommand(0L, 1), "personal-session-1", "corr"));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var session = first.get(10, TimeUnit.SECONDS);
            assertThat(session.meetingId()).isEqualTo(second.get(10, TimeUnit.SECONDS).meetingId());
            assertThat(session.lifecycleState()).isEqualTo("LOBBY");
            assertThat(count("vm_meetings")).isEqualTo(before + 1);
            assertThat(count("vm_personal_meeting_room_sessions")).isOne();
            assertThat(own(() -> rooms.createSession(new RoomSessionCommand(1L, 1), "personal-session-2", "corr")).meetingId())
                    .isEqualTo(session.meetingId());
            assertThat(as(1, 4, all(), () -> rooms.resolve(room.opaqueAlias(), 1)).meetingId()).isEqualTo(session.meetingId());
            verifyNoInteractions(media);
            assertThat(jdbc.queryForMap("SELECT default_microphone_enabled, default_camera_enabled, waiting_room_enabled, guest_access_enabled FROM vm_meetings WHERE meeting_id = ?", session.meetingId()))
                    .containsEntry("default_microphone_enabled", false)
                    .containsEntry("default_camera_enabled", false)
                    .containsEntry("waiting_room_enabled", true)
                    .containsEntry("guest_access_enabled", false);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void differentKeysCannotCreateParallelOpenSessions() throws Exception {
        own(() -> rooms.create(new RoomCreate("My office"), "personal-create-1", "corr"));
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> sessionContender("session-race-key1", ready, start));
            var second = executor.submit(() -> sessionContender("session-race-key2", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            boolean a = first.get(10, TimeUnit.SECONDS);
            boolean b = second.get(10, TimeUnit.SECONDS);
            assertThat(a ^ b).isTrue();
            assertThat(count("vm_personal_meeting_room_sessions")).isOne();
        } finally { executor.shutdownNow(); }
    }

    @Test
    void roomSessionAndMeetingRollbackTogetherWhenWorkspaceAuditFails() {
        own(() -> rooms.create(new RoomCreate("My office"), "personal-create-1", "corr"));
        long before = count("vm_meetings");
        doThrow(new IllegalStateException("audit unavailable")).when(audit).workspaceChanged(
                any(), eq("meeting.personal-room.session-created"), anyString(), anyString(), any(), anyMap());
        assertThatThrownBy(() -> own(() -> rooms.createSession(new RoomSessionCommand(0L, 1), "personal-session-1", "corr")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("vm_meetings")).isEqualTo(before);
        assertThat(count("vm_personal_meeting_room_sessions")).isZero();
        assertThat(count("vm_meeting_workspace_commands")).isOne();
        assertThat(own(() -> rooms.get()).version()).isZero();
        assertThat(count("sys_audit_outbox")).isOne();
    }

    @Test
    void disabledTenantAndStaleRoomGenerationCannotStartAndHistoryIsSelfScoped() {
        own(() -> rooms.create(new RoomCreate("My office"), "personal-create-1", "corr"));
        own(() -> rooms.rotate(new VersionCommand(0L), "personal-rotate-1", "corr"));
        assertThatThrownBy(() -> own(() -> rooms.createSession(new RoomSessionCommand(1L, 1), "personal-session-1", "corr")))
                .isInstanceOf(BaseException.class);
        var session = own(() -> rooms.createSession(new RoomSessionCommand(1L, 2), "personal-session-2", "corr"));
        assertThat(own(() -> rooms.history(0, 30)).items()).extracting(RoomSessionResponse::meetingId)
                .containsExactly(session.meetingId());
        assertThatThrownBy(() -> as(1, 4, all(), () -> rooms.history(0, 30))).isInstanceOf(BaseException.class);
        jdbc.update("UPDATE vm_tenant_policies SET meetings_enabled = FALSE WHERE tenant_id = 1");
        assertThatThrownBy(() -> own(() -> rooms.createSession(new RoomSessionCommand(1L, 2), "personal-session-2", "corr")))
                .isInstanceOf(BaseException.class);
    }

    private boolean sessionContender(String key, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown(); start.await();
        try {
            own(() -> rooms.createSession(new RoomSessionCommand(0L, 1), key, "corr"));
            return true;
        } catch (BaseException expected) { return false; }
    }
}
