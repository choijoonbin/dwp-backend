package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingLiveFacilitationDtos;
import com.dwp.services.meeting.videomeeting.api.MeetingLiveFacilitationDtos.FacilitationCommandResponse;
import com.dwp.services.meeting.videomeeting.api.MeetingLiveFacilitationDtos.PollResponse;
import com.dwp.services.meeting.videomeeting.api.MeetingLiveFacilitationDtos.QuestionResponse;
import com.dwp.services.meeting.videomeeting.api.MeetingLiveFacilitationDtos.TimerResponse;
import com.dwp.services.meeting.videomeeting.audit.MeetingLiveFacilitationAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.AgendaItem;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.FacilitationState;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.Poll;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.PollState;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.Question;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.QuestionState;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.StoredCommand;
import com.dwp.services.meeting.videomeeting.domain.MeetingLiveFacilitationModels.TimerState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.correlation;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHash;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHashesMatch;

@Service
public class MeetingLiveFacilitationService {

    private static final int POLLING_INTERVAL_MILLIS = 3_000;

    private final VideoMeetingRepository meetings;
    private final MeetingLiveFacilitationRepository facilitation;
    private final MeetingLiveFacilitationAuditRecorder audit;
    private final Clock clock;

    @Autowired
    public MeetingLiveFacilitationService(
            VideoMeetingRepository meetings,
            MeetingLiveFacilitationRepository facilitation,
            MeetingLiveFacilitationAuditRecorder audit) {
        this(meetings, facilitation, audit, Clock.systemUTC());
    }

    MeetingLiveFacilitationService(
            VideoMeetingRepository meetings,
            MeetingLiveFacilitationRepository facilitation,
            MeetingLiveFacilitationAuditRecorder audit,
            Clock clock) {
        this.meetings = meetings;
        this.facilitation = facilitation;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MeetingLiveFacilitationDtos.SnapshotResponse snapshot(UUID meetingId) {
        Access access = requireMembership(meetingId, false, false);
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<FacilitationState> stored = facilitation.state(
                access.subject().tenantId(), meetingId, false);
        boolean visible = stored.isPresent()
                && (access.meeting().lifecycleState() == LifecycleState.LIVE
                    || stored.get().retentionUntil().isAfter(now));
        FacilitationState state = visible ? stored.orElseThrow() : emptyState(access, now);
        boolean liveJoined = access.meeting().lifecycleState() == LifecycleState.LIVE
                && access.viewer().attendanceState() == AttendanceState.JOINED;
        boolean canModerate = liveJoined && access.viewer().canHost();
        List<QuestionResponse> questions = visible
                ? facilitation.questions(access.subject().tenantId(), meetingId,
                        access.subject().userId()).stream()
                    .map(question -> QuestionResponse.from(
                            question, access.subject().userId(), canModerate))
                    .toList()
                : List.of();
        List<PollResponse> polls = visible
                ? facilitation.polls(access.subject().tenantId(), meetingId,
                        access.subject().userId()).stream()
                    .map(poll -> PollResponse.from(poll, liveJoined, canModerate))
                    .toList()
                : List.of();
        return new MeetingLiveFacilitationDtos.SnapshotResponse(
                "POLLING", POLLING_INTERVAL_MILLIS, now, state.lastSequence(),
                new MeetingLiveFacilitationDtos.CapabilitiesResponse(
                        liveJoined, liveJoined, liveJoined, canModerate),
                TimerResponse.from(state, now), questions, polls);
    }

    @Transactional
    public FacilitationCommandResponse<QuestionResponse> askQuestion(
            UUID meetingId,
            MeetingLiveFacilitationDtos.AskQuestionCommand request,
            String idempotencyKey,
            String correlationId) {
        Access access = requireMembership(meetingId, true, false);
        String text = requiredText(request == null ? null : request.text(), 2000, "question");
        Command command = command(access, "QUESTION_ASK", idempotencyKey,
                requestHash(meetingId, text));
        if (command.prior().isPresent()) {
            Question prior = requireQuestion(access, command.prior().get().resultResourceId());
            return questionResponse(access, prior, currentSequence(access));
        }
        long sequence = nextSequence(access, command.retentionUntil());
        OffsetDateTime now = command.now();
        Question question = facilitation.createQuestion(
                access.subject().tenantId(), meetingId, access.viewer(), text,
                sequence, now, command.retentionUntil());
        saveCommand(access, command, question.questionId(), question.version());
        audit.command(access.subject(), access.meeting(), "meeting.facilitation.question.asked",
                "MEETING_QUESTION", question.questionId(), correlation(correlationId),
                Map.of("sequence", sequence, "version", question.version(),
                        "state", question.state().name()));
        return questionResponse(access, question, sequence);
    }

    @Transactional
    public FacilitationCommandResponse<QuestionResponse> upvoteQuestion(
            UUID meetingId, UUID questionId, String idempotencyKey, String correlationId) {
        Access access = requireMembership(meetingId, true, false);
        Question current = requireOpenQuestion(access, questionId);
        Command command = command(access, "QUESTION_UPVOTE", idempotencyKey,
                requestHash(meetingId, questionId));
        if (command.prior().isPresent()) {
            return questionResponse(access, requireQuestion(access, questionId),
                    currentSequence(access));
        }
        long sequence = nextSequence(access, command.retentionUntil());
        facilitation.addQuestionUpvote(current, access.viewer(), sequence, command.now());
        Question updated = requireQuestion(access, questionId);
        saveCommand(access, command, questionId, updated.version());
        audit.command(access.subject(), access.meeting(), "meeting.facilitation.question.upvoted",
                "MEETING_QUESTION", questionId, correlation(correlationId),
                Map.of("sequence", sequence, "version", updated.version()));
        return questionResponse(access, updated, sequence);
    }

    @Transactional
    public FacilitationCommandResponse<QuestionResponse> answerQuestion(
            UUID meetingId,
            UUID questionId,
            MeetingLiveFacilitationDtos.AnswerQuestionCommand request,
            String idempotencyKey,
            String correlationId) {
        Access access = requireMembership(meetingId, true, true);
        Question current = requireQuestion(access, questionId);
        String answer = requiredText(request == null ? null : request.answer(), 4000, "answer");
        long expectedVersion = expectedVersion(request == null ? null : request.expectedVersion());
        Command command = command(access, "QUESTION_ANSWER", idempotencyKey,
                requestHash(meetingId, questionId, expectedVersion, answer));
        if (command.prior().isPresent()) {
            return questionResponse(access, requireQuestion(access, questionId),
                    currentSequence(access));
        }
        long sequence = nextSequence(access, command.retentionUntil());
        Question updated = facilitation.moderateQuestion(
                        current, QuestionState.ANSWERED, answer, expectedVersion,
                        sequence, access.subject().userId(), command.now(),
                        access.subject().userId())
                .orElseThrow(this::conflict);
        saveCommand(access, command, questionId, updated.version());
        audit.command(access.subject(), access.meeting(), "meeting.facilitation.question.answered",
                "MEETING_QUESTION", questionId, correlation(correlationId),
                Map.of("sequence", sequence, "version", updated.version(),
                        "state", updated.state().name()));
        return questionResponse(access, updated, sequence);
    }

    @Transactional
    public FacilitationCommandResponse<QuestionResponse> dismissQuestion(
            UUID meetingId,
            UUID questionId,
            MeetingLiveFacilitationDtos.VersionCommand request,
            String idempotencyKey,
            String correlationId) {
        Access access = requireMembership(meetingId, true, true);
        Question current = requireQuestion(access, questionId);
        long expectedVersion = expectedVersion(request == null ? null : request.expectedVersion());
        Command command = command(access, "QUESTION_DISMISS", idempotencyKey,
                requestHash(meetingId, questionId, expectedVersion));
        if (command.prior().isPresent()) {
            return questionResponse(access, requireQuestion(access, questionId),
                    currentSequence(access));
        }
        long sequence = nextSequence(access, command.retentionUntil());
        Question updated = facilitation.moderateQuestion(
                        current, QuestionState.DISMISSED, null, expectedVersion,
                        sequence, access.subject().userId(), command.now(),
                        access.subject().userId())
                .orElseThrow(this::conflict);
        saveCommand(access, command, questionId, updated.version());
        audit.command(access.subject(), access.meeting(), "meeting.facilitation.question.dismissed",
                "MEETING_QUESTION", questionId, correlation(correlationId),
                Map.of("sequence", sequence, "version", updated.version(),
                        "state", updated.state().name()));
        return questionResponse(access, updated, sequence);
    }

    @Transactional
    public FacilitationCommandResponse<PollResponse> createPoll(
            UUID meetingId,
            MeetingLiveFacilitationDtos.CreatePollCommand request,
            String idempotencyKey,
            String correlationId) {
        Access access = requireMembership(meetingId, true, true);
        String question = requiredText(request == null ? null : request.question(), 1000, "poll");
        List<String> options = pollOptions(request == null ? null : request.options());
        boolean anonymous = request == null || request.anonymous();
        Command command = command(access, "POLL_CREATE", idempotencyKey,
                requestHash(meetingId, question, options, anonymous));
        if (command.prior().isPresent()) {
            Poll prior = requirePoll(access, command.prior().get().resultResourceId());
            return pollResponse(access, prior, currentSequence(access));
        }
        long sequence = nextSequence(access, command.retentionUntil());
        Poll poll = facilitation.createPoll(
                access.subject().tenantId(), meetingId, access.viewer(), question, options,
                anonymous, sequence, command.now(), command.retentionUntil());
        saveCommand(access, command, poll.pollId(), poll.version());
        audit.command(access.subject(), access.meeting(), "meeting.facilitation.poll.created",
                "MEETING_POLL", poll.pollId(), correlation(correlationId),
                Map.of("sequence", sequence, "version", poll.version(),
                        "optionCount", options.size(), "state", poll.state().name()));
        return pollResponse(access, poll, sequence);
    }

    @Transactional
    public FacilitationCommandResponse<PollResponse> openPoll(
            UUID meetingId, UUID pollId, MeetingLiveFacilitationDtos.VersionCommand request,
            String idempotencyKey, String correlationId) {
        return transitionPoll(meetingId, pollId, request, PollState.OPEN,
                "POLL_OPEN", "meeting.facilitation.poll.opened", idempotencyKey, correlationId);
    }

    @Transactional
    public FacilitationCommandResponse<PollResponse> closePoll(
            UUID meetingId, UUID pollId, MeetingLiveFacilitationDtos.VersionCommand request,
            String idempotencyKey, String correlationId) {
        return transitionPoll(meetingId, pollId, request, PollState.CLOSED,
                "POLL_CLOSE", "meeting.facilitation.poll.closed", idempotencyKey, correlationId);
    }

    @Transactional
    public FacilitationCommandResponse<PollResponse> vote(
            UUID meetingId,
            UUID pollId,
            MeetingLiveFacilitationDtos.VotePollCommand request,
            String idempotencyKey,
            String correlationId) {
        Access access = requireMembership(meetingId, true, false);
        Poll poll = requirePoll(access, pollId);
        if (poll.state() != PollState.OPEN) throw invalidState("The poll is not open.");
        UUID optionId = request == null ? null : request.optionId();
        if (optionId == null || !facilitation.pollOptionExists(
                access.subject().tenantId(), meetingId, pollId, optionId)) {
            throw notFound("The poll option was not found.");
        }
        long expectedBallotVersion = expectedVersion(
                request == null ? null : request.expectedBallotVersion());
        Command command = command(access, "POLL_VOTE", idempotencyKey,
                requestHash(meetingId, pollId, optionId, expectedBallotVersion));
        if (command.prior().isPresent()) {
            return pollResponse(access, requirePoll(access, pollId),
                    currentSequence(access));
        }
        long sequence = nextSequence(access, command.retentionUntil());
        long ballotVersion = facilitation.castVote(
                        poll, optionId, access.viewer(), expectedBallotVersion,
                        sequence, command.now())
                .orElseThrow(this::conflict);
        Poll updated = requirePoll(access, pollId);
        saveCommand(access, command, pollId, ballotVersion);
        audit.command(access.subject(), access.meeting(), "meeting.facilitation.poll.voted",
                "MEETING_POLL", pollId, correlation(correlationId),
                Map.of("sequence", sequence, "ballotVersion", ballotVersion));
        return pollResponse(access, updated, sequence);
    }

    @Transactional
    public FacilitationCommandResponse<TimerResponse> startTimer(
            UUID meetingId,
            MeetingLiveFacilitationDtos.StartTimerCommand request,
            String idempotencyKey,
            String correlationId) {
        Access access = requireMembership(meetingId, true, true);
        UUID agendaItemId = request == null ? null : request.agendaItemId();
        long expectedVersion = expectedVersion(request == null ? null : request.expectedVersion());
        AgendaItem item = agendaItemId == null ? null : facilitation.agendaItem(
                access.subject().tenantId(), meetingId, agendaItemId).orElse(null);
        if (item == null) throw notFound("The agenda item was not found.");
        return timerCommand(access, "TIMER_START", idempotencyKey,
                requestHash(meetingId, agendaItemId, expectedVersion), correlationId,
                expectedVersion, "meeting.facilitation.timer.started",
                (state, sequence, command) -> facilitation.startTimer(
                        state, item, expectedVersion, sequence, command.now(),
                        command.retentionUntil(), access.subject().userId()));
    }

    @Transactional
    public FacilitationCommandResponse<TimerResponse> pauseTimer(
            UUID meetingId, MeetingLiveFacilitationDtos.VersionCommand request,
            String idempotencyKey, String correlationId) {
        Access access = requireMembership(meetingId, true, true);
        long expectedVersion = expectedVersion(request == null ? null : request.expectedVersion());
        return timerCommand(access, "TIMER_PAUSE", idempotencyKey,
                requestHash(meetingId, expectedVersion), correlationId, expectedVersion,
                "meeting.facilitation.timer.paused",
                (state, sequence, command) -> facilitation.pauseTimer(
                        state, expectedVersion, sequence, command.now(),
                        command.retentionUntil(), access.subject().userId()));
    }

    @Transactional
    public FacilitationCommandResponse<TimerResponse> resumeTimer(
            UUID meetingId, MeetingLiveFacilitationDtos.VersionCommand request,
            String idempotencyKey, String correlationId) {
        Access access = requireMembership(meetingId, true, true);
        long expectedVersion = expectedVersion(request == null ? null : request.expectedVersion());
        return timerCommand(access, "TIMER_RESUME", idempotencyKey,
                requestHash(meetingId, expectedVersion), correlationId, expectedVersion,
                "meeting.facilitation.timer.resumed",
                (state, sequence, command) -> facilitation.resumeTimer(
                        state, expectedVersion, sequence, command.now(),
                        command.retentionUntil(), access.subject().userId()));
    }

    @Transactional
    public FacilitationCommandResponse<TimerResponse> advanceTimer(
            UUID meetingId, MeetingLiveFacilitationDtos.VersionCommand request,
            String idempotencyKey, String correlationId) {
        Access access = requireMembership(meetingId, true, true);
        long expectedVersion = expectedVersion(request == null ? null : request.expectedVersion());
        return timerCommand(access, "TIMER_ADVANCE", idempotencyKey,
                requestHash(meetingId, expectedVersion), correlationId, expectedVersion,
                "meeting.facilitation.timer.advanced",
                (state, sequence, command) -> {
                    if (state.agendaItemId() == null) return Optional.empty();
                    Optional<AgendaItem> next = facilitation.nextAgendaItem(
                            access.subject().tenantId(), meetingId, state.agendaItemId());
                    return next.isPresent()
                            ? facilitation.advanceTimer(
                                state, next.get(), expectedVersion, sequence,
                                command.now(), command.retentionUntil(),
                                access.subject().userId())
                            : facilitation.completeTimer(
                                state, expectedVersion, sequence, command.now(),
                                command.retentionUntil(), access.subject().userId());
                });
    }

    private FacilitationCommandResponse<PollResponse> transitionPoll(
            UUID meetingId, UUID pollId, MeetingLiveFacilitationDtos.VersionCommand request,
            PollState target, String operation, String action,
            String idempotencyKey, String correlationId) {
        Access access = requireMembership(meetingId, true, true);
        Poll current = requirePoll(access, pollId);
        long expectedVersion = expectedVersion(request == null ? null : request.expectedVersion());
        Command command = command(access, operation, idempotencyKey,
                requestHash(meetingId, pollId, target, expectedVersion));
        if (command.prior().isPresent()) {
            return pollResponse(access, requirePoll(access, pollId),
                    currentSequence(access));
        }
        long sequence = nextSequence(access, command.retentionUntil());
        Poll updated = facilitation.transitionPoll(
                        current, target, expectedVersion, sequence, command.now(),
                        access.subject().userId())
                .orElseThrow(this::conflict);
        saveCommand(access, command, pollId, updated.version());
        audit.command(access.subject(), access.meeting(), action, "MEETING_POLL", pollId,
                correlation(correlationId), Map.of(
                        "sequence", sequence, "version", updated.version(),
                        "state", updated.state().name()));
        return pollResponse(access, updated, sequence);
    }

    private FacilitationCommandResponse<TimerResponse> timerCommand(
            Access access, String operation, String idempotencyKey, String hash,
            String correlationId, long expectedVersion, String action, TimerUpdate update) {
        Command command = command(access, operation, idempotencyKey, hash);
        FacilitationState current = facilitation.state(
                access.subject().tenantId(), access.meeting().meetingId(), true).orElseThrow();
        if (command.prior().isPresent()) {
            return timerResponse(current, current.lastSequence(), command.now());
        }
        if (current.timerVersion() != expectedVersion) throw conflict();
        long sequence = nextSequence(access, command.retentionUntil());
        FacilitationState updated = update.apply(current, sequence, command)
                .orElseThrow(this::conflict);
        saveCommand(access, command, null, updated.timerVersion());
        audit.command(access.subject(), access.meeting(), action,
                "MEETING_AGENDA_TIMER", null, correlation(correlationId),
                Map.of("sequence", sequence, "version", updated.timerVersion(),
                        "state", updated.timerState().name()));
        return timerResponse(updated, sequence, command.now());
    }

    private Command command(
            Access access, String operation, String idempotencyKey, String hash) {
        UUID key = idempotencyUuid(idempotencyKey);
        OffsetDateTime now = OffsetDateTime.now(clock);
        TenantPolicy policy = meetings.ensurePolicy(
                access.subject().tenantId(), access.subject().userId());
        if (!policy.meetingsEnabled()) throw forbidden("Meetings are disabled for this tenant.");
        OffsetDateTime retentionUntil = now.plusDays(policy.chatRetentionDays());
        Optional<StoredCommand> prior = facilitation.command(
                access.subject().tenantId(), access.meeting().meetingId(),
                access.subject().userId(), operation, key);
        if (prior.isPresent() && !requestHashesMatch(prior.get().requestSha256(), hash)) {
            throw conflict("The Idempotency-Key was already used with different content.");
        }
        if (prior.isEmpty()) {
            facilitation.ensureState(
                    access.subject().tenantId(), access.meeting().meetingId(),
                    retentionUntil, access.subject().userId());
        }
        return new Command(operation, key, hash, now, retentionUntil, prior);
    }

    private long currentSequence(Access access) {
        return facilitation.state(
                        access.subject().tenantId(), access.meeting().meetingId(), false)
                .map(FacilitationState::lastSequence)
                .orElse(0L);
    }

    private long nextSequence(Access access, OffsetDateTime retentionUntil) {
        return facilitation.nextSequence(
                access.subject().tenantId(), access.meeting().meetingId(),
                retentionUntil, access.subject().userId());
    }

    private void saveCommand(
            Access access, Command command, UUID resultResourceId, long resultVersion) {
        facilitation.saveCommand(
                access.subject().tenantId(), access.meeting().meetingId(),
                access.subject().userId(), command.operation(), command.idempotencyKey(),
                command.requestSha256(), resultResourceId, resultVersion, command.now());
    }

    private Access requireMembership(
            UUID meetingId, boolean requireJoinedLive, boolean requireModerator) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = requireJoinedLive
                ? meetings.lockMeeting(subject.tenantId(), meetingId)
                : meetings.accessibleMeeting(subject.tenantId(), meetingId, subject.userId())
                    .orElseThrow(() -> notFound("The meeting was not found."));
        Participant viewer = meetings.participant(
                        subject.tenantId(), meetingId, subject.userId())
                .orElseThrow(() -> notFound("The meeting was not found."));
        if (!viewer.admitted()) throw forbidden("Meeting admission is required.");
        if (requireJoinedLive && (meeting.lifecycleState() != LifecycleState.LIVE
                || viewer.attendanceState() != AttendanceState.JOINED)) {
            throw invalidState("The participant must be connected to a live meeting.");
        }
        if (requireModerator && !viewer.canHost()) {
            throw forbidden("A host or co-host role is required.");
        }
        return new Access(subject, meeting, viewer);
    }

    private FacilitationState emptyState(Access access, OffsetDateTime now) {
        return new FacilitationState(
                access.subject().tenantId(), access.meeting().meetingId(), 0,
                TimerState.IDLE, null, null, null, 0, null, 0, now);
    }

    private Question requireQuestion(Access access, UUID questionId) {
        if (questionId == null) throw notFound("The question was not found.");
        return facilitation.question(
                        access.subject().tenantId(), access.meeting().meetingId(),
                        questionId, access.subject().userId())
                .orElseThrow(() -> notFound("The question was not found."));
    }

    private Question requireOpenQuestion(Access access, UUID questionId) {
        Question question = requireQuestion(access, questionId);
        if (question.state() != QuestionState.OPEN) {
            throw invalidState("The question is no longer open.");
        }
        return question;
    }

    private Poll requirePoll(Access access, UUID pollId) {
        if (pollId == null) throw notFound("The poll was not found.");
        return facilitation.poll(
                        access.subject().tenantId(), access.meeting().meetingId(),
                        pollId, access.subject().userId())
                .orElseThrow(() -> notFound("The poll was not found."));
    }

    private FacilitationCommandResponse<QuestionResponse> questionResponse(
            Access access, Question question, long sequence) {
        return new FacilitationCommandResponse<>(
                QuestionResponse.from(question, access.subject().userId(),
                        access.viewer().canHost()),
                sequence, OffsetDateTime.now(clock));
    }

    private FacilitationCommandResponse<PollResponse> pollResponse(
            Access access, Poll poll, long sequence) {
        return new FacilitationCommandResponse<>(
                PollResponse.from(poll, true, access.viewer().canHost()),
                sequence, OffsetDateTime.now(clock));
    }

    private FacilitationCommandResponse<TimerResponse> timerResponse(
            FacilitationState state, long sequence, OffsetDateTime now) {
        return new FacilitationCommandResponse<>(TimerResponse.from(state, now), sequence, now);
    }

    private List<String> pollOptions(List<String> values) {
        if (values == null) throw invalidInput("Polls require two to six options.");
        LinkedHashSet<String> canonical = new LinkedHashSet<>();
        List<String> options = new ArrayList<>();
        for (String value : values) {
            String normalized = requiredText(value, 500, "poll option");
            if (canonical.add(normalized.toLowerCase(Locale.ROOT))) options.add(normalized);
        }
        if (options.size() < 2 || options.size() > 6) {
            throw invalidInput("Polls require two to six distinct options.");
        }
        return List.copyOf(options);
    }

    private String requiredText(String value, int limit, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > limit) {
            throw invalidInput(label + " content is outside the allowed length.");
        }
        return normalized;
    }

    private UUID idempotencyUuid(String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim());
        } catch (IllegalArgumentException exception) {
            throw invalidInput("Idempotency-Key must be a UUID.");
        }
    }

    private long expectedVersion(Long value) {
        if (value == null || value < 0) {
            throw invalidInput("An explicit non-negative version is required.");
        }
        return value;
    }

    private BaseException notFound(String message) {
        return new BaseException(ErrorCode.ENTITY_NOT_FOUND, message);
    }

    private BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private BaseException invalidState(String message) {
        return new BaseException(ErrorCode.INVALID_STATE, message);
    }

    private BaseException invalidInput(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException conflict() {
        return conflict("The facilitation state changed. Refresh and retry.");
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private record Access(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            Participant viewer) {
    }

    private record Command(
            String operation,
            UUID idempotencyKey,
            String requestSha256,
            OffsetDateTime now,
            OffsetDateTime retentionUntil,
            Optional<StoredCommand> prior) {
    }

    @FunctionalInterface
    private interface TimerUpdate {
        Optional<FacilitationState> apply(
                FacilitationState state, long sequence, Command command);
    }
}
