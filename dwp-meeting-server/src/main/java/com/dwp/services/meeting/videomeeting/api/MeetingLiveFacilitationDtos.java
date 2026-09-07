package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.FacilitationState;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.Poll;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.Question;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.TimerState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class MeetingLiveFacilitationDtos {

    private MeetingLiveFacilitationDtos() {
    }

    public record AskQuestionCommand(
            @NotBlank @Size(max = 2000) String text) {
    }

    public record AnswerQuestionCommand(
            @NotBlank @Size(max = 4000) String answer,
            @NotNull @PositiveOrZero Long expectedVersion) {
    }

    public record VersionCommand(
            @NotNull @PositiveOrZero Long expectedVersion) {
    }

    public record CreatePollCommand(
            @NotBlank @Size(max = 1000) String question,
            @NotNull @Size(min = 2, max = 6)
                    List<@NotBlank @Size(max = 500) String> options,
            boolean anonymous) {
    }

    public record VotePollCommand(
            @NotNull UUID optionId,
            @NotNull @PositiveOrZero Long expectedBallotVersion) {
    }

    public record StartTimerCommand(
            @NotNull UUID agendaItemId,
            @NotNull @PositiveOrZero Long expectedVersion) {
    }

    public record CapabilitiesResponse(
            boolean meetingLive,
            boolean canAskQuestion,
            boolean canVote,
            boolean canModerate) {
    }

    public record TimerResponse(
            String state,
            UUID agendaItemId,
            String agendaItemTitle,
            Integer plannedSeconds,
            int elapsedSeconds,
            Integer remainingSeconds,
            OffsetDateTime runningSince,
            long version) {

        public static TimerResponse from(FacilitationState state, OffsetDateTime serverTime) {
            int elapsed = state.elapsedSeconds();
            if (state.timerState() == TimerState.RUNNING && state.runningSince() != null) {
                elapsed += Math.max(0, Math.toIntExact(
                        Math.min(Integer.MAX_VALUE,
                                Duration.between(state.runningSince(), serverTime).toSeconds())));
            }
            Integer remaining = state.plannedSeconds() == null
                    ? null : Math.max(0, state.plannedSeconds() - elapsed);
            String effectiveState = remaining != null && remaining == 0
                    && state.timerState() == TimerState.RUNNING
                    ? TimerState.COMPLETED.name() : state.timerState().name();
            return new TimerResponse(
                    effectiveState, state.agendaItemId(), state.agendaItemTitle(),
                    state.plannedSeconds(), elapsed, remaining, state.runningSince(),
                    state.timerVersion());
        }
    }

    public record QuestionResponse(
            UUID questionId,
            String state,
            String text,
            String authorDisplayName,
            String answer,
            int upvoteCount,
            boolean upvotedByMe,
            boolean mine,
            boolean canModerate,
            long version,
            long sequence,
            OffsetDateTime createdAt,
            OffsetDateTime answeredAt) {

        public static QuestionResponse from(
                Question question, long viewerUserId, boolean canModerate) {
            return new QuestionResponse(
                    question.questionId(), question.state().name(), question.text(),
                    question.authorDisplayName(), question.answer(), question.upvoteCount(),
                    question.upvotedByViewer(), question.authorUserId() == viewerUserId,
                    canModerate, question.version(), question.lastSequence(),
                    question.createdAt(), question.answeredAt());
        }
    }

    public record PollOptionResponse(
            UUID optionId,
            int position,
            String label,
            int voteCount) {
    }

    public record PollResponse(
            UUID pollId,
            String state,
            String question,
            boolean anonymous,
            List<PollOptionResponse> options,
            int totalVotes,
            UUID myOptionId,
            long myBallotVersion,
            boolean canVote,
            boolean canModerate,
            long version,
            long sequence,
            OffsetDateTime openedAt,
            OffsetDateTime closedAt) {

        public static PollResponse from(Poll poll, boolean meetingLive, boolean canModerate) {
            return new PollResponse(
                    poll.pollId(), poll.state().name(), poll.question(), poll.anonymous(),
                    poll.options().stream().map(option -> new PollOptionResponse(
                            option.optionId(), option.position(), option.label(),
                            option.voteCount())).toList(),
                    poll.totalVotes(), poll.viewerOptionId(), poll.viewerBallotVersion(),
                    meetingLive && poll.state().name().equals("OPEN"), canModerate,
                    poll.version(), poll.lastSequence(), poll.openedAt(), poll.closedAt());
        }
    }

    public record SnapshotResponse(
            String transport,
            int pollingIntervalMillis,
            OffsetDateTime serverTime,
            long sequence,
            CapabilitiesResponse capabilities,
            TimerResponse timer,
            List<QuestionResponse> questions,
            List<PollResponse> polls) {
    }

    public record FacilitationCommandResponse<T>(
            @Valid T resource,
            long sequence,
            OffsetDateTime serverTime) {
    }
}
