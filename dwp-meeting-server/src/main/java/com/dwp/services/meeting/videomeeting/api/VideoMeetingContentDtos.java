package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.MeetingContentDependencies;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.BlockerCode;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ConsentCounts;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentNotice;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentPlan;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.RecordingSession;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class VideoMeetingContentDtos {

    private VideoMeetingContentDtos() {
    }

    public record UpdateContentPlanCommand(
            boolean recordingRequested,
            boolean transcriptionRequested,
            boolean aiSummaryRequested,
            boolean e2eeEnabled,
            @PositiveOrZero long expectedVersion) {

        @AssertTrue(message = "AI summary requires transcription.")
        public boolean isAiSummaryDependencyValid() {
            return !aiSummaryRequested || transcriptionRequested;
        }
    }

    public record RequestRecordingCommand(@PositiveOrZero long expectedPlanVersion) {
    }

    public record StopRecordingCommand(@PositiveOrZero long expectedSessionVersion) {
    }

    public record BlockerResponse(
            String code,
            String category,
            boolean retryable,
            String description) {

        public static BlockerResponse from(BlockerCode blocker) {
            return new BlockerResponse(
                    blocker.name(),
                    blocker.infrastructure() ? "DEPENDENCY" : "GOVERNANCE",
                    blocker.infrastructure(),
                    description(blocker));
        }

        private static String description(BlockerCode blocker) {
            return switch (blocker) {
                case MEETINGS_DISABLED -> "Meetings are disabled by tenant policy.";
                case PLAN_RECORDING_DISABLED -> "The content plan does not request recording.";
                case MEETING_NOT_LIVE -> "Recording can only be requested for a live meeting.";
                case POLICY_NEVER -> "Tenant policy prohibits recording.";
                case E2EE -> "End-to-end encryption prevents server-side processing.";
                case CONSENT -> "Every admitted participant must acknowledge the current notice.";
                case RECORDING_NOT_ACTIVE -> "There is no active recording session to stop.";
                case MEDIA_PROVIDER -> "The realtime media provider is unavailable.";
                case EGRESS -> "A governed media egress dependency is unavailable.";
                case STORAGE -> "Governed recording storage is unavailable.";
                case KMS -> "The encryption key service is unavailable.";
                case AUDIT -> "Durable audit delivery is unavailable.";
                case STT -> "The speech-to-text dependency is unavailable.";
                case LLM -> "The approved language-model dependency is unavailable.";
            };
        }
    }

    public record ContentDependencyResponse(
            boolean egressAvailable,
            boolean storageAvailable,
            boolean kmsAvailable,
            boolean speechToTextAvailable,
            boolean languageModelAvailable,
            boolean auditAvailable) {

        public static ContentDependencyResponse from(MeetingContentDependencies.Status status) {
            return new ContentDependencyResponse(
                    status.egressAvailable(), status.storageAvailable(),
                    status.kmsAvailable(), status.speechToTextAvailable(),
                    status.languageModelAvailable(), status.auditAvailable());
        }
    }

    public record ContentNoticeResponse(
            UUID noticeId,
            int revision,
            String state,
            String disclosureCode,
            boolean recordingDisclosed,
            boolean transcriptionDisclosed,
            boolean aiSummaryDisclosed,
            OffsetDateTime publishedAt,
            boolean acknowledgedByViewer) {

        public static ContentNoticeResponse from(
                ContentNotice notice, boolean acknowledgedByViewer) {
            if (notice == null) return null;
            return new ContentNoticeResponse(
                    notice.noticeId(), notice.revision(), notice.state().name(),
                    notice.disclosureCode(), notice.recordingDisclosed(),
                    notice.transcriptionDisclosed(), notice.aiSummaryDisclosed(),
                    notice.publishedAt(), acknowledgedByViewer);
        }
    }

    public record ConsentResponse(
            int requiredAcknowledgements,
            int receivedAcknowledgements,
            boolean complete) {

        public static ConsentResponse from(ConsentCounts counts) {
            return new ConsentResponse(
                    counts.required(), counts.acknowledged(), counts.complete());
        }
    }

    public record RecordingSessionResponse(
            UUID recordingSessionId,
            String state,
            long planVersion,
            UUID noticeId,
            OffsetDateTime requestedAt,
            OffsetDateTime stopRequestedAt,
            OffsetDateTime startedAt,
            OffsetDateTime stoppedAt,
            String failureCode,
            long version) {

        public static RecordingSessionResponse from(RecordingSession session) {
            if (session == null) return null;
            return new RecordingSessionResponse(
                    session.recordingSessionId(), session.state().name(),
                    session.planVersion(), session.noticeId(), session.requestedAt(),
                    session.stopRequestedAt(), session.startedAt(), session.stoppedAt(),
                    session.failureCode(), session.version());
        }
    }

    public record ContentPlanResponse(
            UUID meetingId,
            UUID planId,
            boolean recordingRequested,
            boolean transcriptionRequested,
            boolean aiSummaryRequested,
            boolean e2eeEnabled,
            String state,
            List<BlockerResponse> blockers,
            ContentDependencyResponse dependencies,
            ContentNoticeResponse notice,
            ConsentResponse consent,
            RecordingSessionResponse recordingSession,
            long version,
            OffsetDateTime updatedAt) {

        public static ContentPlanResponse from(
                ContentPlan plan,
                List<BlockerCode> blockers,
                MeetingContentDependencies.Status dependencies,
                ContentNotice notice,
                boolean acknowledgedByViewer,
                ConsentCounts consent,
                RecordingSession session) {
            return new ContentPlanResponse(
                    plan.meetingId(), plan.planId(), plan.recordingRequested(),
                    plan.transcriptionRequested(), plan.aiSummaryRequested(),
                    plan.e2eeEnabled(), plan.state().name(),
                    blockers.stream().map(BlockerResponse::from).toList(),
                    ContentDependencyResponse.from(dependencies),
                    ContentNoticeResponse.from(notice, acknowledgedByViewer),
                    ConsentResponse.from(consent), RecordingSessionResponse.from(session),
                    plan.version(), plan.updatedAt());
        }
    }

    public record NoticeAcknowledgementResponse(
            UUID acknowledgementId,
            UUID noticeId,
            int noticeRevision,
            UUID participantId,
            OffsetDateTime acknowledgedAt) {
    }

    public record RecordingCommandResponse(
            boolean accepted,
            String commandState,
            List<BlockerResponse> blockers,
            RecordingSessionResponse recordingSession,
            long contentPlanVersion) {
    }

    public record RecordingCommandResult(
            int httpStatus,
            RecordingCommandResponse response) {

        public boolean accepted() {
            return response.accepted();
        }
    }
}
