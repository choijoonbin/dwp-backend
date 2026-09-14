package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.MeetingParticipantDisconnectDtos.DisconnectCommand;
import com.dwp.services.meeting.videomeeting.api.MeetingMediaWebhookController;
import com.dwp.services.meeting.videomeeting.provider.LiveKitMeetingWebhookAdapter;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaWebhook.EventType;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaWebhook.ParticipantBinding;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaWebhook.ProviderEvent;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaWebhook.RoomBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import io.livekit.server.AccessToken;
import com.google.protobuf.util.JsonFormat;
import livekit.LivekitModels;
import livekit.LivekitWebhook;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MeetingParticipantWebhookCleanupPostgresTest extends MeetingWorkspacePostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Override PostgreSQLContainer<?> postgres() { return POSTGRES; }
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 8, 12, 0, 0, 0, ZoneOffset.UTC);
    private MeetingMediaWebhookRepository repository;
    private MeetingMediaWebhookTransactions webhooks;
    private MeetingMediaWebhookService service;
    private UUID meetingId;
    private UUID participantId;
    private UUID incarnation;
    private String roomName;

    @BeforeEach void configureWatchdog() {
        repository = new MeetingMediaWebhookRepository(jdbc);
        var recovery = new MeetingLifecycleRecoveryProperties();
        recovery.setMaximumAttempts(2);
        var target = new MeetingMediaWebhookTransactions(repository, meetings, audit,
                new MeetingMediaProperties(), recovery, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        webhooks = (MeetingMediaWebhookTransactions) proxy.getProxy();
        service = new MeetingMediaWebhookService(webhooks, media, recovery);
        when(media.capability()).thenReturn(new MeetingMediaProvider.Capability(
                true,"LIVEKIT",null,true,true,true,true,300));
        meetingId = jdbc.queryForObject("SELECT meeting_id FROM vm_meetings WHERE tenant_id=1 AND room_name='dwp-meeting-seed-live-operations'", UUID.class);
        incarnation = UUID.randomUUID();
        roomName = "dwp-meeting-t1-" + meetingId.toString().replace("-", "")
                + "-i" + incarnation.toString().replace("-", "");
        jdbc.update("UPDATE vm_meetings SET media_access_state='ACTIVE',media_incarnation=?,room_name=? WHERE meeting_id=?", incarnation, roomName, meetingId);
        jdbc.update("UPDATE vm_meeting_participants SET attendance_state='JOINED',admitted_at=CURRENT_TIMESTAMP,joined_at=CURRENT_TIMESTAMP,left_at=NULL WHERE meeting_id=? AND user_id IN (4,20)", meetingId);
        participantId = meetings.participant(1,meetingId,20).orElseThrow().participantId();
    }

    private void fenceParticipant() {
        var commands = new MeetingParticipantDisconnectTransactions(
                meetings, new MeetingParticipantDisconnectRepository(jdbc), audit);
        long version = meetings.participant(1,meetingId,participantId).orElseThrow().version();
        as(1,4,all(),() -> commands.request(meetingId,participantId,
                new DisconnectCommand(version),UUID.randomUUID().toString(),"watchdog-test"));
        jdbc.update("UPDATE vm_meeting_participant_disconnects SET command_state='DISCONNECTED',completed_at=CURRENT_TIMESTAMP WHERE meeting_id=?",meetingId);
    }

    @Test void blockedJoinQueuesOnlyTheBoundParticipantAndReplayDoesNotRemoveAgain() {
        fenceParticipant();
        var event = joined("watchdog-joined",incarnation,20);

        service.accept(event);
        service.accept(event);

        assertThat(state(event)).isEqualTo("CLEANED");
        assertThat(attendance()).isEqualTo("DENIED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vm_meeting_provider_connections WHERE participant_id=?",Long.class,participantId)).isZero();
        verify(media).disconnectParticipant(argThat(room -> room.tenantId()==1
                && room.meetingId().equals(meetingId) && room.incarnation().equals(incarnation)
                && room.roomName().equals(roomName)),eq(participantId),eq(20L));
        verify(media,never()).endRoom(anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_outbox WHERE payload->>'action'='meeting.provider.blocked-participant.disconnected'",Long.class)).isOne();
    }

    @Test void normalReconnectAndForeignBindingsNeverQueueParticipantRemoval() {
        jdbc.update("UPDATE vm_meeting_participants SET attendance_state='LEFT',left_at=CURRENT_TIMESTAMP WHERE participant_id=?",participantId);
        var normal = joined("normal-reconnect",incarnation,20);
        service.accept(normal);
        assertThat(attendance()).isEqualTo("JOINED");
        assertThat(state(normal)).isEqualTo("APPLIED");
        fenceParticipant();
        var oldRoom = joined("old-room-incarnation",UUID.randomUUID(),20);
        var foreignUser = joined("foreign-user",incarnation,42);
        service.accept(oldRoom);
        service.accept(foreignUser);
        assertThat(state(oldRoom)).isEqualTo("IGNORED");
        assertThat(state(foreignUser)).isEqualTo("IGNORED");
        verify(media,never()).disconnectParticipant(any(),any(),anyLong());
        verify(media,never()).endRoom(anyString());
    }

    @Test void cleanupRetriesRespectBackoffAndMaximumAttemptsWithoutEndingTheHostRoom() {
        fenceParticipant();
        doThrow(new IllegalStateException("provider unavailable")).when(media)
                .disconnectParticipant(any(),any(),anyLong());
        var event = joined("provider-retry-bound",incarnation,20);
        service.accept(event);
        assertThat(state(event)).isEqualTo("CLEANUP_FAILED");
        service.cleanupRevokedRooms();
        verify(media,times(1)).disconnectParticipant(any(),any(),anyLong());
        jdbc.update("UPDATE vm_meeting_provider_events SET next_cleanup_at=? WHERE provider_event_id=?",NOW.minusSeconds(1),event.eventId());
        service.cleanupRevokedRooms();
        jdbc.update("UPDATE vm_meeting_provider_events SET next_cleanup_at=? WHERE provider_event_id=?",NOW.minusSeconds(1),event.eventId());
        service.cleanupRevokedRooms();
        verify(media,times(2)).disconnectParticipant(any(),any(),anyLong());
        verify(media,never()).endRoom(anyString());
        assertThat(jdbc.queryForObject("SELECT cleanup_attempt_count FROM vm_meeting_provider_events WHERE provider_event_id=?",Integer.class,event.eventId())).isEqualTo(2);
        assertThat(attendance()).isEqualTo("DENIED");
    }

    @Test void cleanupLeaseRejectsStaleCompletionAndCannotBeClaimedByTwoWorkers() {
        fenceParticipant();
        var event = joined("lease-bound",incarnation,20);
        webhooks.apply(event);
        var first = webhooks.claimCleanup().orElseThrow();
        assertThat(first.participant().participantId()).isEqualTo(participantId);
        assertThat(webhooks.claimCleanup()).isEmpty();
        jdbc.update("UPDATE vm_meeting_provider_events SET cleanup_lease_expires_at=? WHERE provider_event_id=?",NOW.minusSeconds(1),event.eventId());
        var second = webhooks.claimCleanup().orElseThrow();
        assertThatThrownBy(() -> webhooks.cleanupSucceeded(first)).isInstanceOf(BaseException.class);
        webhooks.cleanupSucceeded(second);
        assertThat(state(event)).isEqualTo("CLEANED");
    }

    @Test void auditFailureRollsBackWebhookReservationAndNeverCallsTheProvider() {
        fenceParticipant();
        doThrow(new IllegalStateException("audit unavailable")).when(audit)
                .providerParticipant(anyLong(),any(),any(),eq("meeting.provider.blocked-participant.joined"),any(),any());
        var event = joined("audit-rollback",incarnation,20);
        assertThatThrownBy(() -> service.accept(event)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vm_meeting_provider_events WHERE provider_event_id=?",Long.class,event.eventId())).isZero();
        assertThat(attendance()).isEqualTo("DENIED");
        verify(media,never()).disconnectParticipant(any(),any(),anyLong());
        verify(media,never()).endRoom(anyString());
    }

    @Test void missingFenceDuringRecoveryFailsClosedAndNeverFallsBackToDeletingTheRoom() {
        fenceParticipant();
        var event = joined("binding-removed",incarnation,20);
        webhooks.apply(event);
        jdbc.update("DELETE FROM vm_meeting_participant_disconnects WHERE meeting_id=?",meetingId);
        service.cleanupRevokedRooms();
        assertThat(state(event)).isEqualTo("CLEANUP_FAILED");
        verify(media,never()).disconnectParticipant(any(),any(),anyLong());
        verify(media,never()).endRoom(anyString());
    }

    @Test void signedHttpWebhookTraversesOfficialVerificationBeforeDurableCleanup() throws Exception {
        fenceParticipant();
        var properties = new MeetingMediaProperties();
        properties.getLivekit().setApiKey("watchdog-test-key");
        properties.getLivekit().setApiSecret("watchdog-test-secret-at-least-32-characters");
        var controller = new MeetingMediaWebhookController(new LiveKitMeetingWebhookAdapter(properties),service);
        var event = joined("signed-watchdog",incarnation,20);
        String participantMetadata = "{\"schemaVersion\":1,\"tenantId\":1,\"meetingId\":\""
                + meetingId + "\",\"participantId\":\"" + participantId
                + "\",\"roomIncarnation\":\"" + incarnation
                + "\",\"userId\":20,\"meetingRole\":\"ATTENDEE\",\"reactionsAllowed\":false}";
        var boundRoom = new MeetingMediaProvider.PreparedRoom("LIVEKIT",roomName,1,meetingId,incarnation);
        String body = JsonFormat.printer().omittingInsignificantWhitespace().print(
                LivekitWebhook.WebhookEvent.newBuilder().setId(event.eventId())
                        .setEvent("participant_joined").setCreatedAt(NOW.toEpochSecond())
                        .setRoom(LivekitModels.Room.newBuilder().setSid("RM_watchdog")
                                .setName(roomName).setMetadata(boundRoom.roomMetadata())
                                .setCreationTime(NOW.minusMinutes(1).toEpochSecond()))
                        .setParticipant(LivekitModels.ParticipantInfo.newBuilder()
                                .setSid("PA_signed_watchdog").setIdentity(event.participant().identity())
                                .setMetadata(participantMetadata).setJoinedAt(NOW.toEpochSecond()))
                        .build());
        assertThatThrownBy(() -> controller.receive(signedRequest(body,"forged-secret")))
                .isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vm_meeting_provider_events WHERE provider_event_id=?",Long.class,event.eventId())).isZero();
        verify(media,never()).disconnectParticipant(any(),any(),anyLong());

        assertThat(controller.receive(signedRequest(body,properties.getLivekit().getApiSecret()))
                .getStatusCode().value()).isEqualTo(204);
        assertThat(controller.receive(signedRequest(body,properties.getLivekit().getApiSecret()))
                .getStatusCode().value()).isEqualTo(204);
        assertThat(state(event)).isEqualTo("CLEANED");
        verify(media).disconnectParticipant(any(),eq(participantId),eq(20L));
        verify(media,never()).endRoom(anyString());
    }

    private MockHttpServletRequest signedRequest(String body, String secret) throws Exception {
        var auth = new AccessToken("watchdog-test-key",secret);
        auth.setSha256(Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8))));
        auth.setTtl(300_000L);
        var request = new MockHttpServletRequest("POST",MeetingMediaWebhookController.PATH);
        request.addHeader("Authorization",auth.toJwt());
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private ProviderEvent joined(String id, UUID eventIncarnation, long eventUser) {
        return new ProviderEvent("LIVEKIT",id,EventType.PARTICIPANT_JOINED,NOW,
                new RoomBinding("RM_watchdog",roomName,1,meetingId,eventIncarnation,NOW.minusMinutes(1)),
                new ParticipantBinding("PA_" + id,participantId,eventUser,
                        "tenant:1:meeting:"+meetingId+":participant:"+participantId
                                +":incarnation:"+eventIncarnation+":user:"+eventUser,NOW));
    }
    private String state(ProviderEvent event) {
        return jdbc.queryForObject("SELECT processing_state FROM vm_meeting_provider_events WHERE provider_event_id=?",String.class,event.eventId());
    }
    private String attendance() { return meetings.participant(1,meetingId,participantId).orElseThrow().attendanceState().name(); }
}
