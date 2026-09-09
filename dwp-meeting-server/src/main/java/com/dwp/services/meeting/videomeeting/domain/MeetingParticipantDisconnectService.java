package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.api.MeetingParticipantDisconnectController.DisconnectCommand;
import com.dwp.services.meeting.videomeeting.api.MeetingParticipantDisconnectController.DisconnectResponse;
import com.dwp.services.meeting.videomeeting.domain.MeetingParticipantDisconnectRepository.Command;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class MeetingParticipantDisconnectService {
    private final MeetingParticipantDisconnectTransactions transactions;
    private final MeetingMediaProvider media;
    private final MeetingLifecycleRecoveryProperties recovery;
    MeetingParticipantDisconnectService(MeetingParticipantDisconnectTransactions transactions,
            MeetingMediaProvider media, MeetingLifecycleRecoveryProperties recovery) {
        this.transactions = transactions; this.media = media; this.recovery = recovery;
    }
    public DisconnectResponse disconnect(UUID meetingId, UUID participantId, DisconnectCommand input,
                                         String key, String correlationId) {
        var command = transactions.request(meetingId, participantId, input, key, correlationId);
        boolean completed = "DISCONNECTED".equals(command.state());
        if (!completed) {
            var claim = transactions.claim(command.commandId(), recovery.getMaximumAttempts());
            if (claim.isPresent()) completed = execute(claim.orElseThrow());
        }
        return new DisconnectResponse(meetingId, participantId, command.commandId(),
                completed ? "DISCONNECTED" : "PENDING", true);
    }
    @Scheduled(fixedDelayString = "${dwp.meeting.lifecycle-recovery.poll-delay:PT10S}")
    public void recover() {
        if (!recovery.isEnabled() || !media.capability().available()) return;
        for (int index = 0; index < recovery.getBatchSize(); index++) {
            var claim = transactions.claimNext(recovery.getMaximumAttempts());
            if (claim.isEmpty()) return;
            execute(claim.orElseThrow());
        }
    }
    private boolean execute(Command command) {
        try {
            media.disconnectParticipant(new MeetingMediaProvider.PreparedRoom("LIVEKIT",
                    command.roomName(), command.tenantId(), command.meetingId(), command.incarnation()),
                    command.participantId(), command.participantUserId());
            transactions.complete(command);
            return true;
        } catch (RuntimeException failure) {
            try { transactions.failed(command, recovery.getRetryDelay()); }
            catch (RuntimeException stale) { failure.addSuppressed(stale); }
            return false;
        }
    }
}
