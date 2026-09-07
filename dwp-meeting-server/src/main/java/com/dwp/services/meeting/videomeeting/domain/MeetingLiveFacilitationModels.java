package com.dwp.services.meeting.videomeeting.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class MeetingLiveFacilitationModels {

    private MeetingLiveFacilitationModels() {
    }

    public enum TimerState {
        IDLE, RUNNING, PAUSED, COMPLETED
    }

    public enum QuestionState {
        OPEN, ANSWERED, DISMISSED
    }

    public enum PollState {
        DRAFT, OPEN, CLOSED
    }

    public record FacilitationState(
            long tenantId,
            UUID meetingId,
            long lastSequence,
            TimerState timerState,
            UUID agendaItemId,
            String agendaItemTitle,
            Integer plannedSeconds,
            int elapsedSeconds,
            OffsetDateTime runningSince,
            long timerVersion,
            OffsetDateTime retentionUntil) {
    }

    public record AgendaItem(
            UUID itemId,
            String title,
            int position,
            int plannedSeconds) {
    }

    public record Question(
            UUID questionId,
            long tenantId,
            UUID meetingId,
            UUID authorParticipantId,
            long authorUserId,
            String authorDisplayName,
            QuestionState state,
            String text,
            String answer,
            int upvoteCount,
            boolean upvotedByViewer,
            long version,
            long lastSequence,
            OffsetDateTime createdAt,
            OffsetDateTime answeredAt,
            OffsetDateTime retentionUntil) {
    }

    public record PollOption(
            UUID optionId,
            int position,
            String label,
            int voteCount) {
    }

    public record Poll(
            UUID pollId,
            long tenantId,
            UUID meetingId,
            PollState state,
            String question,
            boolean anonymous,
            List<PollOption> options,
            int totalVotes,
            UUID viewerOptionId,
            long viewerBallotVersion,
            long version,
            long lastSequence,
            OffsetDateTime openedAt,
            OffsetDateTime closedAt,
            OffsetDateTime retentionUntil) {
    }

    public record StoredCommand(
            String requestSha256,
            UUID resultResourceId,
            long resultVersion) {
    }

    public record ExpiredFacilitation(
            long tenantId,
            UUID meetingId) {
    }
}
