package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AccessScope;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.MeetingDetail;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.PersonSnapshot;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.commandKey;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.correlation;

@Service
public class VideoMeetingService {

    private final VideoMeetingRepository repository;
    private final MeetingMediaProvider mediaProvider;
    private final MeetingJoinCodeGenerator joinCodeGenerator;
    private final VideoMeetingCreationCoordinator creation;
    private final VideoMeetingLifecycleCoordinator lifecycle;
    private final VideoMeetingContentAdmissionGuard contentAdmissionGuard;
    private final VideoMeetingAuditRecorder audit;
    private final Clock clock;

    @Autowired
    public VideoMeetingService(
            VideoMeetingRepository repository,
            MeetingMediaProvider mediaProvider,
            MeetingJoinCodeGenerator joinCodeGenerator,
            VideoMeetingLifecycleCoordinator lifecycle,
            VideoMeetingContentAdmissionGuard contentAdmissionGuard,
            VideoMeetingAuditRecorder audit) {
        this(repository, mediaProvider, joinCodeGenerator, lifecycle, contentAdmissionGuard,
                audit, Clock.systemUTC());
    }

    VideoMeetingService(
            VideoMeetingRepository repository,
            MeetingMediaProvider mediaProvider,
            MeetingJoinCodeGenerator joinCodeGenerator,
            VideoMeetingAuditRecorder audit,
            Clock clock) {
        this(repository, mediaProvider, joinCodeGenerator, null, null, audit, clock);
    }

    VideoMeetingService(
            VideoMeetingRepository repository,
            MeetingMediaProvider mediaProvider,
            MeetingJoinCodeGenerator joinCodeGenerator,
            VideoMeetingLifecycleCoordinator lifecycle,
            VideoMeetingContentAdmissionGuard contentAdmissionGuard,
            VideoMeetingAuditRecorder audit,
            Clock clock) {
        this.repository = repository;
        this.mediaProvider = mediaProvider;
        this.joinCodeGenerator = joinCodeGenerator;
        this.creation = new VideoMeetingCreationCoordinator(
                repository, joinCodeGenerator, audit);
        this.lifecycle = lifecycle;
        this.contentAdmissionGuard = contentAdmissionGuard;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public VideoMeetingDtos.CapabilityResponse capabilities() {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        TenantPolicy policy = repository.ensurePolicy(subject.tenantId(), subject.userId());
        return VideoMeetingDtos.CapabilityResponse.from(
                mediaProvider.capability(), policy);
    }

    @Transactional
    public VideoMeetingDtos.HomeResponse home(String requestedTimeZone) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        TenantPolicy policy = repository.ensurePolicy(subject.tenantId(), subject.userId());
        ZoneId timeZone = validTimeZone(requestedTimeZone);
        OffsetDateTime now = OffsetDateTime.now(clock).atZoneSameInstant(timeZone).toOffsetDateTime();
        VideoMeetingModels.HomeProjection home = repository.home(
                subject.tenantId(), subject.userId(), now);
        List<VideoMeetingDtos.MeetingSummary> live = home.live().stream()
                .map(VideoMeetingDtos::summary).toList();
        List<VideoMeetingDtos.MeetingSummary> upcoming = home.upcoming().stream()
                .map(VideoMeetingDtos::summary).toList();
        LocalDate today = now.toLocalDate();
        List<VideoMeetingDtos.MeetingSummary> todayMeetings =
                java.util.stream.Stream.concat(live.stream(), upcoming.stream())
                        .filter(meeting -> meeting.lifecycleState().equals("LIVE")
                                || meeting.startsAt() != null
                                && meeting.startsAt().atZoneSameInstant(timeZone)
                                        .toLocalDate().equals(today))
                        .distinct()
                        .toList();
        return new VideoMeetingDtos.HomeResponse(
                now, timeZone.getId(), VideoMeetingDtos.CapabilityResponse.from(
                        mediaProvider.capability(), policy),
                live.isEmpty() ? null : live.getFirst(),
                upcoming.isEmpty() ? null : upcoming.getFirst(),
                todayMeetings,
                home.recent().stream().map(VideoMeetingDtos::summary).toList(),
                VideoMeetingDtos.HomeMetricsResponse.from(home.metrics()));
    }

    @Transactional(readOnly = true)
    public List<VideoMeetingDtos.MeetingPersonResponse> people(String query, int limit) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int boundedLimit = Math.max(1, Math.min(50, limit));
        return repository.searchPeople(
                        subject.tenantId(), subject.userId(), normalizedQuery, boundedLimit)
                .stream()
                .map(VideoMeetingDtos.MeetingPersonResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public VideoMeetingDtos.PageResponse<VideoMeetingDtos.MeetingSummary> meetings(
            int page, int pageSize) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.max(1, Math.min(100, pageSize));
        VideoMeetingRepository.PagedMeetings meetings = repository.meetings(
                subject.tenantId(), subject.userId(), boundedPage, boundedSize);
        return new VideoMeetingDtos.PageResponse<>(
                meetings.items().stream().map(VideoMeetingDtos::summary).toList(),
                meetings.total(), boundedPage, boundedSize);
    }

    @Transactional
    public VideoMeetingDtos.MeetingCreatedResponse instant(
            VideoMeetingDtos.InstantMeetingRequest request,
            String idempotencyKey,
            String correlationId) {
        return creation.create(
                request.title(), request.description(), request.agenda(),
                null, null, "Asia/Seoul", request.accessScope(),
                request.waitingRoomEnabled(), request.guestAccessEnabled(),
                request.allowJoinBeforeHost(), request.defaultMicrophoneEnabled(),
                request.defaultCameraEnabled(),
                request.participantUserIds(), request.guestInvitees(),
                LifecycleState.LOBBY, idempotencyKey, correlationId);
    }

    @Transactional
    public VideoMeetingDtos.MeetingCreatedResponse schedule(
            VideoMeetingDtos.ScheduleMeetingRequest request,
            String idempotencyKey,
            String correlationId) {
        validateSchedule(request);
        return creation.create(
                request.title(), request.description(), request.agenda(),
                request.startsAt(), request.startsAt().plusMinutes(request.durationMinutes()),
                request.timeZone(),
                request.accessScope(), request.waitingRoomEnabled(),
                request.guestAccessEnabled(), request.allowJoinBeforeHost(),
                request.defaultMicrophoneEnabled(), request.defaultCameraEnabled(),
                request.participantUserIds(),
                request.guestInvitees(), LifecycleState.SCHEDULED,
                idempotencyKey, correlationId);
    }

    @Transactional(readOnly = true)
    public VideoMeetingDtos.MeetingDetailResponse detail(UUID meetingId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = accessible(subject, meetingId);
        Optional<Participant> viewer = repository.participant(
                subject.tenantId(), meetingId, subject.userId());
        ParticipantRole viewerRole = viewer.map(Participant::participantRole).orElse(null);
        boolean canHost = meeting.organizerUserId() == subject.userId()
                || viewer.map(Participant::canHost).orElse(false);
        boolean recapVisible = canHost || meeting.lifecycleState() == LifecycleState.ENDED
                && viewer.map(Participant::admitted).orElse(false);
        return VideoMeetingDtos.MeetingDetailResponse.from(
                repository.detail(meeting), viewerRole, recapVisible, canHost);
    }

    @Transactional(readOnly = true)
    public VideoMeetingDtos.JoinCodeResolutionResponse resolveCode(String code) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        String normalized = joinCodeGenerator.normalize(code);
        Meeting meeting = repository.resolveCode(subject.tenantId(), normalized)
                .orElseThrow(joinCodeGenerator::invalidCode);
        Optional<Participant> membership = repository.participant(
                subject.tenantId(), meeting.meetingId(), subject.userId());
        if (meeting.accessScope() == AccessScope.INVITED && membership.isEmpty()) {
            throw joinCodeGenerator.invalidCode();
        }
        boolean requestRequired = meeting.waitingRoomEnabled()
                && membership
                        .map(participant -> !participant.admitted())
                        .orElse(true);
        MeetingDetail detail = repository.detail(meeting);
        ParticipantRole viewerRole = membership
                .map(Participant::participantRole).orElse(null);
        return new VideoMeetingDtos.JoinCodeResolutionResponse(
                VideoMeetingDtos.summary(meeting, detail.participants().size(), viewerRole),
                !meeting.terminal(), meeting.terminal() ? "MEETING_UNAVAILABLE" : null,
                requestRequired);
    }

    @Transactional
    public VideoMeetingDtos.JoinRequestResponse requestJoin(
            UUID meetingId,
            VideoMeetingDtos.JoinRequestCommand request,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        String commandKey = commandKey(idempotencyKey);
        Meeting meeting = repository.lockMeeting(subject.tenantId(), meetingId);
        requireJoinable(meeting);
        TenantPolicy policy = requireEnabledPolicy(subject);
        Participant participant = repository.participant(
                        subject.tenantId(), meetingId, subject.userId())
                .orElseGet(() -> createWalkInParticipant(subject, meeting, policy));
        if (participant.admitted() || participant.attendanceState() == AttendanceState.REQUESTED) {
            return VideoMeetingDtos.JoinRequestResponse.from(participant);
        }
        boolean autoAdmit = !meeting.waitingRoomEnabled()
                && participant.participantRole() != ParticipantRole.GUEST;
        Participant requested = repository.requestJoin(
                subject.tenantId(), meetingId, participant.participantId(),
                autoAdmit, subject.userId());
        String eventCorrelation = correlation(correlationId);
        Map<String, Object> evidence = Map.of(
                "autoAdmitted", autoAdmit,
                "policyWaitingRoom", policy.waitingRoomRequired());
        repository.recordEvent(
                meeting, requested, subject.userId(), "JOIN_REQUESTED",
                eventCorrelation, commandKey, evidence);
        audit.participantAccess(
                subject, meeting, requested, "meeting.join.requested",
                eventCorrelation, "SUCCESS", evidence);
        return VideoMeetingDtos.JoinRequestResponse.from(requested);
    }

    @Transactional(readOnly = true)
    public VideoMeetingDtos.JoinRequestResponse joinRequest(
            UUID meetingId, UUID requestId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = accessible(subject, meetingId);
        Participant request = repository.participant(
                        subject.tenantId(), meetingId, requestId)
                .orElseThrow(() -> notFound("The meeting join request was not found."));
        boolean ownRequest = Objects.equals(request.userId(), subject.userId());
        if (!ownRequest) requireHost(subject, meeting);
        return VideoMeetingDtos.JoinRequestResponse.from(request);
    }

    @Transactional(readOnly = true)
    public VideoMeetingDtos.LobbyResponse lobby(UUID meetingId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = accessible(subject, meetingId);
        requireHost(subject, meeting);
        return new VideoMeetingDtos.LobbyResponse(
                repository.waitingParticipants(subject.tenantId(), meetingId).stream()
                        .map(VideoMeetingDtos.JoinRequestResponse::from).toList());
    }

    @Transactional
    public VideoMeetingDtos.JoinRequestResponse decideAdmission(
            UUID meetingId,
            UUID participantId,
            boolean admit,
            VideoMeetingDtos.AdmissionCommand request,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = repository.lockMeeting(subject.tenantId(), meetingId);
        requireHost(subject, meeting);
        Participant current = repository.participant(subject.tenantId(), meetingId, participantId)
                .orElseThrow(() -> notFound("The meeting participant was not found."));
        AttendanceState target = admit ? AttendanceState.ADMITTED : AttendanceState.DENIED;
        if (current.attendanceState() == target) {
            return VideoMeetingDtos.JoinRequestResponse.from(current);
        }
        Participant decided = repository.decideAdmission(
                subject.tenantId(), meetingId, participantId, admit,
                subject.userId(), request.expectedVersion());
        String eventCorrelation = correlation(correlationId);
        Map<String, Object> evidence = Map.of(
                "decision", admit ? "ADMITTED" : "DENIED");
        repository.recordEvent(
                meeting, decided, subject.userId(), admit ? "ADMITTED" : "DENIED",
                eventCorrelation, commandKey(idempotencyKey), evidence);
        audit.participantAccess(
                subject, meeting, decided,
                admit ? "meeting.participant.admitted" : "meeting.participant.denied",
                eventCorrelation, admit ? "SUCCESS" : "DENIED", evidence);
        return VideoMeetingDtos.JoinRequestResponse.from(decided);
    }

    public VideoMeetingDtos.MeetingDetailResponse start(
            UUID meetingId,
            VideoMeetingDtos.VersionedCommand request,
            String idempotencyKey,
            String correlationId) {
        return lifecycle.start(meetingId, request, idempotencyKey, correlationId);
    }

    @Transactional
    public VideoMeetingDtos.ParticipantTokenResponse token(
            UUID meetingId,
            VideoMeetingDtos.IssueTokenCommand request,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = repository.lockAccessibleMeeting(
                        subject.tenantId(), meetingId, subject.userId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND, "The meeting was not found."));
        if (!meeting.live()) throw invalidState("The meeting is not live.");
        VideoMeetingRepository.MediaSession mediaSession = repository.mediaSession(
                        subject.tenantId(), meetingId)
                .filter(VideoMeetingRepository.MediaSession::active)
                .orElseThrow(() -> invalidState(
                        "The meeting media session is closing or unavailable."));
        TenantPolicy policy = requireEnabledPolicy(subject);
        MeetingMediaProvider.Capability capability = requireProvider();
        Participant participant = repository.participant(
                        subject.tenantId(), meetingId, subject.userId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.FORBIDDEN, "Meeting admission is required."));
        if (!participant.admitted()) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Meeting admission is required.");
        }
        if (request != null && request.joinRequestId() != null
                && !request.joinRequestId().equals(participant.participantId())) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Meeting admission is required.");
        }
        requireCurrentContentNotice(subject, meetingId, participant);
        OffsetDateTime issuedAt = OffsetDateTime.now(clock);
        MeetingMediaProvider.EffectivePermissions permissions = effectivePermissions(
                capability, policy);
        MeetingMediaProvider.ParticipantToken token = mediaProvider.issueParticipantToken(
                meeting, participant, subject, permissions, issuedAt,
                mediaSession.incarnation());
        String eventCorrelation = correlation(correlationId);
        Map<String, Object> evidence = Map.of("expiresAt", token.expiresAt().toString());
        repository.recordEvent(
                meeting, participant, subject.userId(), "TOKEN_ISSUED", eventCorrelation,
                null, evidence);
        audit.participantAccess(
                subject, meeting, participant, "meeting.media-token.issued",
                eventCorrelation, "SUCCESS", evidence);
        return new VideoMeetingDtos.ParticipantTokenResponse(
                meetingId, participant.participantId().toString(), meeting.provider(),
                token.serverUrl(), token.token(), participant.participantRole().name(),
                token.expiresAt(),
                VideoMeetingDtos.EffectivePermissionsResponse.from(permissions));
    }

    @Transactional
    public VideoMeetingDtos.ParticipantResponse connected(
            UUID meetingId, String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = accessible(subject, meetingId);
        if (!meeting.live()) throw invalidState("The meeting is not live.");
        Participant participant = repository.participant(
                        subject.tenantId(), meetingId, subject.userId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.FORBIDDEN, "Meeting admission is required."));
        if (!participant.admitted()) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Meeting admission is required.");
        }
        requireCurrentContentNotice(subject, meetingId, participant);
        if (participant.attendanceState() != AttendanceState.JOINED) {
            throw invalidState(
                    "The media provider has not confirmed this participant connection.");
        }
        return VideoMeetingDtos.ParticipantResponse.from(participant);
    }

    private void requireCurrentContentNotice(
            MeetingRequestContext.Subject subject,
            UUID meetingId,
            Participant participant) {
        if (contentAdmissionGuard != null) {
            contentAdmissionGuard.requireCurrentNoticeAcknowledgement(
                    subject.tenantId(), meetingId, participant.participantId());
        }
    }

    @Transactional
    public VideoMeetingDtos.ParticipantResponse leave(UUID meetingId, String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = accessible(subject, meetingId);
        Participant participant = repository.participant(
                        subject.tenantId(), meetingId, subject.userId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.FORBIDDEN, "The meeting participant was not found."));
        if (participant.attendanceState() != AttendanceState.LEFT) {
            throw invalidState(
                    "The media provider has not confirmed this participant departure.");
        }
        return VideoMeetingDtos.ParticipantResponse.from(participant);
    }

    public VideoMeetingDtos.MeetingDetailResponse end(
            UUID meetingId,
            VideoMeetingDtos.VersionedCommand request,
            String idempotencyKey,
            String correlationId) {
        return lifecycle.end(meetingId, request, idempotencyKey, correlationId);
    }

    @Transactional
    public VideoMeetingDtos.PolicyResponse policy() {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        return VideoMeetingDtos.PolicyResponse.from(
                repository.ensurePolicy(subject.tenantId(), subject.userId()));
    }

    @Transactional
    public VideoMeetingDtos.PolicyResponse updatePolicy(
            VideoMeetingDtos.TenantPolicyUpdateRequest request,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        if (request.artifactRetentionDays() > request.retentionDays()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Artifact retention cannot exceed meeting retention.");
        }
        int effectiveChatRetention = request.chatRetentionDays() == null
                ? repository.policy(subject.tenantId())
                        .map(TenantPolicy::chatRetentionDays).orElse(90)
                : request.chatRetentionDays();
        if (effectiveChatRetention > request.retentionDays()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Chat retention cannot exceed meeting retention.");
        }
        if (request.guestsAllowed() || request.allowJoinBeforeHost()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "External access and joining before the host require a verified identity rollout.");
        }
        repository.ensurePolicy(subject.tenantId(), subject.userId());
        TenantPolicy updated = repository.updatePolicy(
                subject.tenantId(), request, subject.userId());
        String eventCorrelation = correlation(correlationId);
        repository.recordPolicyEvent(
                subject.tenantId(), subject.userId(), updated.version(),
                eventCorrelation, commandKey(idempotencyKey),
                "NEVER");
        audit.policyChanged(subject, updated.version(), eventCorrelation, Map.of(
                "meetingsEnabled", updated.meetingsEnabled(),
                "waitingRoomRequired", updated.waitingRoomRequired(),
                "guestsAllowed", updated.guestsAllowed(),
                "maximumParticipants", updated.maximumParticipants(),
                "recordingPolicy", updated.recordingPolicy(),
                "retentionDays", updated.retentionDays(),
                "artifactRetentionDays", updated.artifactRetentionDays(),
                "chatRetentionDays", updated.chatRetentionDays()));
        return VideoMeetingDtos.PolicyResponse.from(updated);
    }

    @Transactional(readOnly = true)
    public VideoMeetingDtos.PageResponse<VideoMeetingDtos.HistoryItemResponse> history(
            int page, int pageSize) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.max(1, Math.min(100, pageSize));
        VideoMeetingRepository.PagedMeetings history = repository.history(
                subject.tenantId(), subject.userId(), boundedPage, boundedSize);
        return new VideoMeetingDtos.PageResponse<>(
                history.items().stream().map(VideoMeetingDtos::history).toList(),
                history.total(), boundedPage, boundedSize);
    }

    @Transactional
    public VideoMeetingDtos.AdminOverviewResponse adminOverview(String requestedTimeZone) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        TenantPolicy policy = repository.ensurePolicy(subject.tenantId(), subject.userId());
        ZoneId zoneId = validTimeZone(requestedTimeZone);
        OffsetDateTime now = OffsetDateTime.now(clock).atZoneSameInstant(zoneId).toOffsetDateTime();
        OffsetDateTime dayStart = now.toLocalDate().atStartOfDay(zoneId).toOffsetDateTime();
        VideoMeetingRepository.AdminOverviewData data = repository.adminOverview(
                subject.tenantId(), dayStart, dayStart.plusDays(1), now.minusDays(7));
        MeetingMediaProvider.Capability capability = mediaProvider.capability();
        return new VideoMeetingDtos.AdminOverviewResponse(
                data.liveMeetings(), data.scheduledToday(), data.waitingParticipants(),
                data.meetingsLastSevenDays(), null, data.failedJoinAttempts(),
                new VideoMeetingDtos.AdminCapabilitiesResponse(
                        capability.video(), capability.screenShare(),
                        policy.participantChatAllowed(), false, false, false, false));
    }

    private Participant createWalkInParticipant(
            MeetingRequestContext.Subject subject, Meeting meeting, TenantPolicy policy) {
        if (meeting.accessScope() == AccessScope.INVITED) {
            throw notFound("The meeting is unavailable for this account.");
        }
        if (repository.activeParticipantCount(subject.tenantId(), meeting.meetingId())
                >= policy.maximumParticipants()) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT, "The meeting participant limit was reached.");
        }
        PersonSnapshot person = repository.person(subject.tenantId(), subject.userId())
                .orElseThrow(() -> notFound("The workforce projection was not found."));
        return repository.addInternalParticipant(
                meeting, person, ParticipantRole.ATTENDEE,
                AttendanceState.INVITED, subject.userId());
    }

    private void validateSchedule(VideoMeetingDtos.ScheduleMeetingRequest request) {
        validTimeZone(request.timeZone());
    }

    private ZoneId validTimeZone(String value) {
        String normalized = value == null || value.isBlank() ? "UTC" : value.trim();
        try {
            return ZoneId.of(normalized);
        } catch (java.time.DateTimeException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The time zone is invalid.");
        }
    }

    private Meeting accessible(MeetingRequestContext.Subject subject, UUID meetingId) {
        return repository.accessibleMeeting(subject.tenantId(), meetingId, subject.userId())
                .orElseThrow(() -> notFound("The meeting was not found."));
    }

    private Participant requireHost(MeetingRequestContext.Subject subject, Meeting meeting) {
        Participant participant = repository.participant(
                        subject.tenantId(), meeting.meetingId(), subject.userId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.FORBIDDEN, "A meeting host role is required."));
        if (!participant.canHost()) {
            throw new BaseException(ErrorCode.FORBIDDEN, "A meeting host role is required.");
        }
        return participant;
    }

    private TenantPolicy requireEnabledPolicy(MeetingRequestContext.Subject subject) {
        TenantPolicy policy = repository.ensurePolicy(subject.tenantId(), subject.userId());
        if (!policy.meetingsEnabled()) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Meetings are disabled for this tenant.");
        }
        return policy;
    }

    private void requireJoinable(Meeting meeting) {
        if (meeting.lifecycleState() != LifecycleState.SCHEDULED
                && meeting.lifecycleState() != LifecycleState.LOBBY
                && meeting.lifecycleState() != LifecycleState.LIVE) {
            throw joinCodeGenerator.invalidCode();
        }
    }

    private MeetingMediaProvider.Capability requireProvider() {
        MeetingMediaProvider.Capability capability = mediaProvider.capability();
        if (!capability.available()) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Realtime meeting provider is unavailable. Inspect capabilities first.");
        }
        return capability;
    }

    private MeetingMediaProvider.EffectivePermissions effectivePermissions(
            MeetingMediaProvider.Capability capability,
            TenantPolicy policy) {
        boolean reactions = policy.reactionsAllowed();
        return new MeetingMediaProvider.EffectivePermissions(
                capability.audio(),
                capability.video(),
                capability.screenShare() && policy.screenShareAllowed(),
                capability.participantList(),
                policy.participantChatAllowed(),
                reactions,
                reactions);
    }

    private BaseException notFound(String message) {
        return new BaseException(ErrorCode.ENTITY_NOT_FOUND, message);
    }

    private BaseException invalidState(String message) {
        return new BaseException(ErrorCode.INVALID_STATE, message);
    }

    private BaseException versionConflict() {
        return new BaseException(
                ErrorCode.OBJECT_VERSION_CONFLICT,
                "The meeting version changed. Refresh and retry.");
    }
}
