package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.Preparation;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.Result;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import org.springframework.stereotype.Service;

import java.util.UUID;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.commandKey;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.correlation;

/** Orchestrates media I/O between short, independently committed database transactions. */
@Service
class VideoMeetingLifecycleCoordinator {

    private final VideoMeetingLifecycleTransactions transactions;
    private final MeetingMediaProvider mediaProvider;

    VideoMeetingLifecycleCoordinator(
            VideoMeetingLifecycleTransactions transactions,
            MeetingMediaProvider mediaProvider) {
        this.transactions = transactions;
        this.mediaProvider = mediaProvider;
    }

    VideoMeetingDtos.MeetingDetailResponse start(
            UUID meetingId,
            VideoMeetingDtos.VersionedCommand request,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Preparation preparation = transactions.prepareStart(
                subject, meetingId, request.expectedVersion(),
                commandKey(idempotencyKey), correlation(correlationId));
        if (!preparation.execute()) return response(preparation);
        try {
            mediaProvider.ensureRoom(
                    preparation.room(), preparation.maximumParticipants());
        } catch (RuntimeException providerFailure) {
            markProviderFailure(preparation, providerFailure);
            throw providerFailure;
        }
        Result result = transactions.completeStart(subject, preparation.operation());
        return response(result);
    }

    VideoMeetingDtos.MeetingDetailResponse end(
            UUID meetingId,
            VideoMeetingDtos.VersionedCommand request,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Preparation preparation = transactions.prepareEnd(
                subject, meetingId, request.expectedVersion(),
                commandKey(idempotencyKey), correlation(correlationId));
        if (!preparation.execute()) return response(preparation);
        try {
            mediaProvider.endRoom(preparation.room().roomName());
        } catch (RuntimeException providerFailure) {
            markProviderFailure(preparation, providerFailure);
            throw providerFailure;
        }
        Result result = transactions.completeEnd(subject, preparation.operation());
        return response(result);
    }

    private void markProviderFailure(
            Preparation preparation, RuntimeException providerFailure) {
        try {
            transactions.failProvider(preparation.operation());
        } catch (RuntimeException fenceFailure) {
            providerFailure.addSuppressed(fenceFailure);
        }
    }

    private VideoMeetingDtos.MeetingDetailResponse response(Preparation preparation) {
        return VideoMeetingDtos.MeetingDetailResponse.from(
                preparation.replayDetail(), preparation.viewerRole());
    }

    private VideoMeetingDtos.MeetingDetailResponse response(Result result) {
        return VideoMeetingDtos.MeetingDetailResponse.from(
                result.detail(), result.viewerRole());
    }
}
