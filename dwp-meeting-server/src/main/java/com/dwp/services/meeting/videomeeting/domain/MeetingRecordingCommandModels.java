package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.api.VideoMeetingContentDtos;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentPlan;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.RecordingSession;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;

import java.time.OffsetDateTime;
import java.util.UUID;

final class MeetingRecordingCommandModels {

    private MeetingRecordingCommandModels() {
    }

    enum CommandType {
        START, STOP
    }

    enum CommandState {
        RUNNING, SUCCEEDED, FAILED
    }

    record ProviderCommand(
            UUID commandId,
            long tenantId,
            UUID meetingId,
            UUID recordingSessionId,
            CommandType commandType,
            CommandState commandState,
            long actorUserId,
            String idempotencyKey,
            String requestSha256,
            String correlationId,
            UUID executionFence,
            OffsetDateTime leaseExpiresAt,
            int attemptCount,
            String providerCode,
            String providerCommandId,
            String failureCode) {
    }

    record Preparation(
            boolean execute,
            Meeting meeting,
            ContentPlan plan,
            RecordingSession session,
            ProviderCommand command,
            VideoMeetingContentDtos.RecordingCommandResult replay) {

        static Preparation execute(
                Meeting meeting,
                ContentPlan plan,
                RecordingSession session,
                ProviderCommand command) {
            return new Preparation(true, meeting, plan, session, command, null);
        }

        static Preparation replay(VideoMeetingContentDtos.RecordingCommandResult result) {
            return new Preparation(false, null, null, null, null, result);
        }
    }
}
