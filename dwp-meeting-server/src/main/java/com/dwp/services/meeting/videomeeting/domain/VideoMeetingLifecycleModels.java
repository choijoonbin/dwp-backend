package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.MeetingDetail;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;

import java.time.OffsetDateTime;
import java.util.UUID;

final class VideoMeetingLifecycleModels {

    private VideoMeetingLifecycleModels() {
    }

    enum OperationType {
        START, END
    }

    enum OperationState {
        RUNNING, SUCCEEDED, FAILED
    }

    record MediaOperation(
            UUID operationId,
            long tenantId,
            UUID meetingId,
            OperationType operationType,
            OperationState operationState,
            long actorUserId,
            long expectedMeetingVersion,
            String idempotencyKey,
            String requestSha256,
            String correlationId,
            UUID executionFence,
            OffsetDateTime leaseExpiresAt,
            int attemptCount,
            String providerCode,
            String providerRoomName,
            UUID roomIncarnation) {
    }

    record Preparation(
            boolean execute,
            MediaOperation operation,
            MeetingMediaProvider.PreparedRoom room,
            int maximumParticipants,
            MeetingDetail replayDetail,
            ParticipantRole viewerRole) {

        static Preparation execute(
                MediaOperation operation,
                MeetingMediaProvider.PreparedRoom room,
                int maximumParticipants,
                ParticipantRole viewerRole) {
            return new Preparation(
                    true, operation, room, maximumParticipants, null, viewerRole);
        }

        static Preparation replay(MeetingDetail detail, ParticipantRole viewerRole) {
            return new Preparation(false, null, null, 0, detail, viewerRole);
        }
    }

    record Result(MeetingDetail detail, ParticipantRole viewerRole) {
    }
}
