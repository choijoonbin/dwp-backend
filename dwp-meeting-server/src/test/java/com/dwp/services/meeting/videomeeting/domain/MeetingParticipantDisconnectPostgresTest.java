package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.MeetingParticipantDisconnectDtos.DisconnectCommand;
import com.dwp.services.meeting.videomeeting.domain.MeetingParticipantDisconnectRepository.Command;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MeetingParticipantDisconnectPostgresTest extends MeetingWorkspacePostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Override PostgreSQLContainer<?> postgres() { return POSTGRES; }
    private MeetingParticipantDisconnectRepository repository;
    private MeetingParticipantDisconnectTransactions commands;
    private MeetingParticipantDisconnectService service;
    private UUID meetingId;
    private UUID participantId;
    private UUID incarnation;
    @BeforeEach void configure() {
        repository = new MeetingParticipantDisconnectRepository(jdbc);
        var target = new MeetingParticipantDisconnectTransactions(meetings, repository, audit);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        commands = (MeetingParticipantDisconnectTransactions) proxy.getProxy();
        service = new MeetingParticipantDisconnectService(commands, media, new MeetingLifecycleRecoveryProperties());
        when(media.capability()).thenReturn(new MeetingMediaProvider.Capability(true,"LIVEKIT",null,true,true,true,true,300));
        meetingId = jdbc.queryForObject("SELECT meeting_id FROM vm_meetings WHERE tenant_id=1 AND room_name='dwp-meeting-seed-live-operations'", UUID.class);
        jdbc.update("UPDATE vm_meetings SET media_access_state='ACTIVE' WHERE meeting_id=?", meetingId);
        incarnation = meetings.mediaSession(1, meetingId).orElseThrow().incarnation();
        jdbc.update("UPDATE vm_meeting_participants SET attendance_state='JOINED', admitted_at=CURRENT_TIMESTAMP, joined_at=CURRENT_TIMESTAMP, left_at=NULL WHERE meeting_id=? AND user_id IN (4,20)", meetingId);
        participantId = meetings.participant(1, meetingId, 20).orElseThrow().participantId();
    }
    private DisconnectCommand input() { return new DisconnectCommand(meetings.participant(1, meetingId, participantId).orElseThrow().version()); }
    private Command request(DisconnectCommand input, String key) {
        return as(1,4,all(),() -> commands.request(meetingId, participantId, input, key, "disconnect-test"));
    }
    @Test void bindsAuthorityVersionIdempotencyAndCurrentRoomFence() {
        var input = input(); String key = UUID.randomUUID().toString();
        var command = request(input,key);
        assertThat(meetings.participantMediaBlocked(1,meetingId,participantId)).isTrue();
        assertThat(meetings.participantMediaBlocked(2,meetingId,participantId)).isFalse();
        assertThat(meetings.participant(1,meetingId,participantId).orElseThrow().attendanceState().name()).isEqualTo("DENIED");
        assertThat(request(input,key).commandId()).isEqualTo(command.commandId());
        assertThatThrownBy(() -> request(input,UUID.randomUUID().toString())).isInstanceOf(BaseException.class);
        assertThat(count("vm_meeting_participant_disconnects")).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_outbox WHERE payload->>'action'='meeting.participant.disconnect.requested'",Long.class)).isOne();
        jdbc.update("UPDATE vm_meetings SET media_incarnation=? WHERE meeting_id=?", UUID.randomUUID(), meetingId);
        assertThat(meetings.participantMediaBlocked(1,meetingId,participantId)).isFalse();
    }
    @Test void deniesNonHostCrossTenantSelfOrganizerAndStaleVersion() {
        var input = input();
        assertThatThrownBy(() -> as(1,20,all(),() -> commands.request(meetingId,participantId,input,UUID.randomUUID().toString(),null))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> as(2,4,all(),() -> commands.request(meetingId,participantId,input,UUID.randomUUID().toString(),null))).isInstanceOf(BaseException.class);
        var organizer = meetings.participant(1,meetingId,4).orElseThrow();
        assertThatThrownBy(() -> as(1,4,all(),() -> commands.request(meetingId,organizer.participantId(),new DisconnectCommand(organizer.version()),UUID.randomUUID().toString(),null))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> request(new DisconnectCommand(input.expectedVersion()+1),UUID.randomUUID().toString())).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> as(1,4,Set.of("APP.MEETINGS:VIEW"),() -> commands.request(meetingId,participantId,input,UUID.randomUUID().toString(),null))).isInstanceOf(BaseException.class);
        assertThat(count("vm_meeting_participant_disconnects")).isZero();
    }
    @Test void auditFailureRollsBackParticipantAndFence() {
        doThrow(new IllegalStateException("audit unavailable")).when(audit).participantAccess(any(),any(),any(),any(),any(),any(),any());
        assertThatThrownBy(() -> request(input(),UUID.randomUUID().toString())).isInstanceOf(IllegalStateException.class);
        assertThat(count("vm_meeting_participant_disconnects")).isZero();
        assertThat(meetings.participant(1,meetingId,participantId).orElseThrow().attendanceState().name()).isEqualTo("JOINED");
    }
    @Test void publicJoinAndTokenCommandsCannotBypassTheCurrentSessionFence() {
        request(input(),UUID.randomUUID().toString());
        assertThatThrownBy(() -> as(1,20,all(),() -> meetingService.requestJoin(meetingId,null,UUID.randomUUID().toString(),null)))
                .isInstanceOf(BaseException.class).hasMessageContaining("connection was ended");
        // Even an independent admission update cannot undo the room-incarnation token fence.
        jdbc.update("UPDATE vm_meeting_participants SET attendance_state='ADMITTED' WHERE participant_id=?",participantId);
        assertThatThrownBy(() -> as(1,20,all(),() -> meetingService.token(meetingId,null,null)))
                .isInstanceOf(BaseException.class).hasMessageContaining("admission is required");
        verify(media,never()).issueParticipantToken(any(),any(),any(),any(),any(),any());
    }
    @Test void providerFailureKeepsFenceAndRecoveryCompletesWithBoundCommand() {
        doThrow(new IllegalStateException("provider unavailable")).doNothing().when(media).disconnectParticipant(any(),any(),anyLong());
        String key = UUID.randomUUID().toString(); var input = input();
        var first = as(1,4,all(),() -> service.disconnect(meetingId,participantId,input,key,"test"));
        assertThat(first.state()).isEqualTo("PENDING");
        assertThat(meetings.participantMediaBlocked(1,meetingId,participantId)).isTrue();
        jdbc.update("UPDATE vm_meeting_participant_disconnects SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        service.recover();
        assertThat(repository.find(1,meetingId,participantId,incarnation).orElseThrow().state()).isEqualTo("DISCONNECTED");
        verify(media,times(2)).disconnectParticipant(argThat(room -> room.meetingId().equals(meetingId) && room.tenantId()==1 && room.incarnation().equals(incarnation)),eq(participantId),eq(20L));
        var replay = as(1,4,all(),() -> service.disconnect(meetingId,participantId,input,key,"test"));
        assertThat(replay.state()).isEqualTo("DISCONNECTED");
        verifyNoMoreInteractionsExceptCapability();
    }
    @Test void unavailableProviderStillCommitsTheCurrentSessionFenceAndDurableCommand() {
        when(media.capability()).thenReturn(new MeetingMediaProvider.Capability(
                false,"disabled","MEDIA_PROVIDER_UNAVAILABLE",false,false,false,false,0));
        doThrow(new UnsupportedOperationException("provider unavailable"))
                .when(media).disconnectParticipant(any(), any(), anyLong());

        var response = as(1,4,all(),() -> service.disconnect(
                meetingId,participantId,input(),UUID.randomUUID().toString(),"test"));

        assertThat(response.state()).isEqualTo("PENDING");
        assertThat(response.blockedForCurrentSession()).isTrue();
        assertThat(meetings.participantMediaBlocked(1,meetingId,participantId)).isTrue();
        assertThat(repository.find(1,meetingId,participantId,incarnation).orElseThrow().state())
                .isEqualTo("PENDING");
    }
    @Test void nullCommandFailsBeforeAnyFenceOrParticipantMutation() {
        assertThatThrownBy(() -> as(1,4,all(),() -> commands.request(
                meetingId,participantId,null,UUID.randomUUID().toString(),null)))
                .isInstanceOf(BaseException.class);
        assertThat(count("vm_meeting_participant_disconnects")).isZero();
        assertThat(meetings.participant(1,meetingId,participantId).orElseThrow()
                .attendanceState().name()).isEqualTo("JOINED");
    }
    private void verifyNoMoreInteractionsExceptCapability() {
        verify(media,times(2)).disconnectParticipant(any(),any(),anyLong());
    }
    @Test void leasedCommandsRejectStaleCompletionAndOnlyOneWorkerClaims() {
        var command = request(input(),UUID.randomUUID().toString());
        var first = commands.claim(command.commandId(),8).orElseThrow();
        assertThat(commands.claim(command.commandId(),8)).isEmpty();
        jdbc.update("UPDATE vm_meeting_participant_disconnects SET lease_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        var next = commands.claimNext(8).orElseThrow();
        assertThatThrownBy(() -> commands.complete(first)).isInstanceOf(BaseException.class);
        commands.complete(next);
        assertThat(repository.find(1,meetingId,participantId,incarnation).orElseThrow().state()).isEqualTo("DISCONNECTED");
    }
    @Test void recoveryStopsAtTheConfiguredAttemptCeilingWhileKeepingTheFence() {
        var bounded = new MeetingLifecycleRecoveryProperties();
        bounded.setMaximumAttempts(2);
        bounded.setRetryDelay(java.time.Duration.ofSeconds(1));
        var boundedService = new MeetingParticipantDisconnectService(commands,media,bounded);
        doThrow(new IllegalStateException("provider unavailable"))
                .when(media).disconnectParticipant(any(),any(),anyLong());

        var response = as(1,4,all(),() -> boundedService.disconnect(
                meetingId,participantId,input(),UUID.randomUUID().toString(),"bounded-test"));
        assertThat(response.state()).isEqualTo("PENDING");
        jdbc.update("UPDATE vm_meeting_participant_disconnects SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        boundedService.recover();
        jdbc.update("UPDATE vm_meeting_participant_disconnects SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        boundedService.recover();

        verify(media,times(2)).disconnectParticipant(any(),eq(participantId),eq(20L));
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM vm_meeting_participant_disconnects WHERE meeting_id=?",Integer.class,meetingId)).isEqualTo(2);
        assertThat(meetings.participantMediaBlocked(1,meetingId,participantId)).isTrue();
    }
}
