package com.dwp.services.meeting.videomeeting.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class VideoMeetingContentModels {

    private VideoMeetingContentModels() {
    }

    public enum PlanState {
        DISABLED, BLOCKED, READY
    }

    public enum NoticeState {
        PUBLISHED, SUPERSEDED
    }

    public enum RecordingState {
        REQUESTED, STARTING, RECORDING, STOP_REQUESTED, STOPPED, FAILED;

        public boolean active() {
            return this == REQUESTED || this == STARTING || this == RECORDING
                    || this == STOP_REQUESTED;
        }
    }

    public enum BlockerCode {
        MEETINGS_DISABLED(false),
        PLAN_RECORDING_DISABLED(false),
        MEETING_NOT_LIVE(false),
        POLICY_NEVER(false),
        E2EE(false),
        CONSENT(false),
        RECORDING_NOT_ACTIVE(false),
        MEDIA_PROVIDER(true),
        EGRESS(true),
        STORAGE(true),
        KMS(true),
        AUDIT(true),
        STT(true),
        LLM(true);

        private final boolean infrastructure;

        BlockerCode(boolean infrastructure) {
            this.infrastructure = infrastructure;
        }

        public boolean infrastructure() {
            return infrastructure;
        }
    }

    public record ContentPlan(
            UUID planId,
            long tenantId,
            UUID meetingId,
            boolean recordingRequested,
            boolean transcriptionRequested,
            boolean aiSummaryRequested,
            boolean e2eeEnabled,
            PlanState state,
            UUID currentNoticeId,
            int noticeRevision,
            long version,
            OffsetDateTime updatedAt) {

        public boolean processingRequested() {
            return recordingRequested || transcriptionRequested || aiSummaryRequested;
        }
    }

    public record ContentNotice(
            UUID noticeId,
            long tenantId,
            UUID meetingId,
            int revision,
            NoticeState state,
            String disclosureCode,
            boolean recordingDisclosed,
            boolean transcriptionDisclosed,
            boolean aiSummaryDisclosed,
            OffsetDateTime publishedAt) {
    }

    public record ConsentCounts(int required, int acknowledged) {

        public boolean complete() {
            return required > 0 && required == acknowledged;
        }
    }

    public record NoticeAcknowledgement(
            UUID acknowledgementId,
            UUID noticeId,
            UUID participantId,
            OffsetDateTime acknowledgedAt) {
    }

    public record RecordingSession(
            UUID recordingSessionId,
            long tenantId,
            UUID meetingId,
            long planVersion,
            UUID noticeId,
            RecordingState state,
            OffsetDateTime requestedAt,
            Long requestedBy,
            OffsetDateTime stopRequestedAt,
            Long stopRequestedBy,
            OffsetDateTime startedAt,
            OffsetDateTime stoppedAt,
            OffsetDateTime failedAt,
            String failureCode,
            long version) {
    }

    public record StoredCommand(
            String requestHash,
            boolean accepted,
            int httpStatus,
            List<BlockerCode> blockers,
            UUID resultResourceId,
            long resultVersion) {
    }
}
