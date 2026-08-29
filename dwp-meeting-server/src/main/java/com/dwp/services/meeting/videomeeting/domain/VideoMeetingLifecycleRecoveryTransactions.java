package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.MediaOperation;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
class VideoMeetingLifecycleRecoveryTransactions {

    private final VideoMeetingLifecycleOperationRepository operations;
    private final MeetingMediaProperties mediaProperties;
    private final MeetingLifecycleRecoveryProperties recoveryProperties;
    private final Clock clock;

    @Autowired
    VideoMeetingLifecycleRecoveryTransactions(
            VideoMeetingLifecycleOperationRepository operations,
            MeetingMediaProperties mediaProperties,
            MeetingLifecycleRecoveryProperties recoveryProperties) {
        this(operations, mediaProperties, recoveryProperties, Clock.systemUTC());
    }

    VideoMeetingLifecycleRecoveryTransactions(
            VideoMeetingLifecycleOperationRepository operations,
            MeetingMediaProperties mediaProperties,
            MeetingLifecycleRecoveryProperties recoveryProperties,
            Clock clock) {
        this.operations = operations;
        this.mediaProperties = mediaProperties;
        this.recoveryProperties = recoveryProperties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<MediaOperation> claim() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return operations.claimRecoverable(
                UUID.randomUUID(), now,
                now.plus(mediaProperties.getLifecycleOperationLease()),
                recoveryProperties.getMaximumAttempts());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(MediaOperation operation) {
        OffsetDateTime failedAt = OffsetDateTime.now(clock);
        operations.failProvider(
                operation, failedAt, failedAt.plus(recoveryProperties.getRetryDelay()));
    }
}
