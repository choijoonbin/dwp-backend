package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.MediaOperation;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.OperationType;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;

/** Reconciles durable media commands without holding a transaction during provider I/O. */
@Service
@ConditionalOnProperty(prefix = "dwp.meeting", name = "provider", havingValue = "livekit")
class VideoMeetingLifecycleRecoveryService {

    private final VideoMeetingLifecycleRecoveryTransactions recovery;
    private final VideoMeetingLifecycleTransactions lifecycle;
    private final MeetingMediaProvider mediaProvider;
    private final MeetingLifecycleRecoveryProperties properties;

    VideoMeetingLifecycleRecoveryService(
            VideoMeetingLifecycleRecoveryTransactions recovery,
            VideoMeetingLifecycleTransactions lifecycle,
            MeetingMediaProvider mediaProvider,
            MeetingLifecycleRecoveryProperties properties) {
        this.recovery = recovery;
        this.lifecycle = lifecycle;
        this.mediaProvider = mediaProvider;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${dwp.meeting.lifecycle-recovery.poll-delay:PT10S}")
    public void recover() {
        if (!properties.isEnabled() || !mediaProvider.capability().available()) return;
        for (int index = 0; index < properties.getBatchSize(); index++) {
            MediaOperation operation = recovery.claim().orElse(null);
            if (operation == null) return;
            execute(operation);
        }
    }

    private void execute(MediaOperation operation) {
        try {
            MeetingMediaProvider.PreparedRoom room = new MeetingMediaProvider.PreparedRoom(
                    operation.providerCode(), operation.providerRoomName(),
                    operation.tenantId(), operation.meetingId(), operation.roomIncarnation());
            if (operation.operationType() == OperationType.START) {
                mediaProvider.ensureRoom(room, maximumParticipants(operation));
                lifecycle.completeStart(subject(operation), operation);
            } else {
                mediaProvider.endRoom(operation.providerRoomName());
                lifecycle.completeEnd(subject(operation), operation);
            }
        } catch (RuntimeException failure) {
            try {
                recovery.failed(operation);
            } catch (RuntimeException staleFence) {
                failure.addSuppressed(staleFence);
            }
        }
    }

    private int maximumParticipants(MediaOperation operation) {
        return lifecycle.maximumParticipants(operation.tenantId());
    }

    private MeetingRequestContext.Subject subject(MediaOperation operation) {
        return new MeetingRequestContext.Subject(
                operation.actorUserId(), operation.tenantId(), null,
                "Meeting lifecycle recovery", Set.of("SYSTEM_RECOVERY"), Set.of(), Set.of());
    }
}
