package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.MediaOperation;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.OperationState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.OperationType;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.Preparation;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.Result;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.LifecycleState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHash;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHashesMatch;

@Service
class VideoMeetingLifecycleTransactions {

    private final VideoMeetingRepository meetings;
    private final VideoMeetingLifecycleOperationRepository operations;
    private final MeetingMediaProvider mediaProvider;
    private final MeetingMediaProperties mediaProperties;
    private final VideoMeetingAuditRecorder audit;
    private final Clock clock;

    @Autowired
    VideoMeetingLifecycleTransactions(
            VideoMeetingRepository meetings,
            VideoMeetingLifecycleOperationRepository operations,
            MeetingMediaProvider mediaProvider,
            MeetingMediaProperties mediaProperties,
            VideoMeetingAuditRecorder audit) {
        this(meetings, operations, mediaProvider, mediaProperties, audit, Clock.systemUTC());
    }

    VideoMeetingLifecycleTransactions(
            VideoMeetingRepository meetings,
            VideoMeetingLifecycleOperationRepository operations,
            MeetingMediaProvider mediaProvider,
            MeetingMediaProperties mediaProperties,
            VideoMeetingAuditRecorder audit,
            Clock clock) {
        this.meetings = meetings;
        this.operations = operations;
        this.mediaProvider = mediaProvider;
        this.mediaProperties = mediaProperties;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Preparation prepareStart(
            MeetingRequestContext.Subject subject,
            UUID meetingId,
            long expectedVersion,
            String idempotencyKey,
            String correlationId) {
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        Participant host = requireHost(subject, meeting);
        if (meeting.live()) {
            return Preparation.replay(meetings.detail(meeting), host.participantRole());
        }
        if (meeting.terminal()) {
            throw invalidState("A completed or cancelled meeting cannot be started.");
        }
        requireVersion(meeting, expectedVersion);
        TenantPolicy policy = requireEnabledPolicy(subject);
        MeetingMediaProvider.Capability capability = requireProvider();
        UUID incarnation = incarnation(
                subject.tenantId(), meeting.meetingId(), idempotencyKey);
        MeetingMediaProvider.PreparedRoom room = mediaProvider.planRoom(
                meeting.meetingId(), meeting.tenantId(), incarnation);
        validateRoomPlan(capability, room);
        MediaOperation operation = prepareOperation(
                subject, meeting, OperationType.START, expectedVersion,
                idempotencyKey, correlationId, room);
        return Preparation.execute(
                operation, room, policy.maximumParticipants(), host.participantRole());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Preparation prepareEnd(
            MeetingRequestContext.Subject subject,
            UUID meetingId,
            long expectedVersion,
            String idempotencyKey,
            String correlationId) {
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        Participant host = requireHost(subject, meeting);
        if (meeting.lifecycleState() == LifecycleState.ENDED) {
            return Preparation.replay(meetings.detail(meeting), host.participantRole());
        }
        if (!meeting.live()) throw invalidState("Only a live meeting can be ended.");
        requireVersion(meeting, expectedVersion);
        MeetingMediaProvider.Capability capability = requireProvider();
        VideoMeetingRepository.MediaSession media = meetings.mediaSession(
                subject.tenantId(), meeting.meetingId())
                .orElseThrow(() -> invalidState("The meeting media session is unavailable."));
        MeetingMediaProvider.PreparedRoom room = new MeetingMediaProvider.PreparedRoom(
                meeting.provider(), meeting.roomName(), meeting.tenantId(), meeting.meetingId(),
                media.incarnation());
        validateRoomPlan(capability, room);
        MediaOperation operation = prepareOperation(
                subject, meeting, OperationType.END, expectedVersion,
                idempotencyKey, correlationId, room);
        meetings.beginEnding(meeting, media.incarnation(), expectedVersion);
        return Preparation.execute(operation, room, 0, host.participantRole());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result completeStart(
            MeetingRequestContext.Subject subject,
            MediaOperation requested) {
        Meeting meeting = meetings.lockMeeting(requested.tenantId(), requested.meetingId());
        MediaOperation operation = currentOperation(requested, OperationType.START);
        requireVersion(meeting, operation.expectedMeetingVersion());
        if (meeting.terminal() || meeting.live()) {
            throw invalidState("The meeting cannot accept this start completion.");
        }
        Meeting started = meetings.start(
                meeting, operation.providerCode(), operation.providerRoomName(),
                operation.roomIncarnation(), subject.userId(),
                operation.expectedMeetingVersion());
        Map<String, Object> evidence = Map.of("provider", operation.providerCode());
        meetings.recordEvent(
                started, null, subject.userId(), "STARTED", operation.correlationId(),
                operation.idempotencyKey(), evidence);
        audit.meetingLifecycle(
                subject, started, "meeting.started", operation.correlationId(), evidence);
        operations.succeed(operation, OffsetDateTime.now(clock));
        ParticipantRole viewerRole = meetings.participant(
                        subject.tenantId(), started.meetingId(), subject.userId())
                .map(Participant::participantRole).orElse(ParticipantRole.ATTENDEE);
        return new Result(meetings.detail(started), viewerRole);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result completeEnd(
            MeetingRequestContext.Subject subject,
            MediaOperation requested) {
        Meeting meeting = meetings.lockMeeting(requested.tenantId(), requested.meetingId());
        MediaOperation operation = currentOperation(requested, OperationType.END);
        requireVersion(meeting, operation.expectedMeetingVersion());
        if (!meeting.live()) {
            throw invalidState("The meeting cannot accept this end completion.");
        }
        Meeting ended = meetings.end(
                meeting, subject.userId(), operation.expectedMeetingVersion());
        Map<String, Object> evidence = Map.of("provider", operation.providerCode());
        meetings.recordEvent(
                ended, null, subject.userId(), "ENDED", operation.correlationId(),
                operation.idempotencyKey(), evidence);
        audit.meetingLifecycle(
                subject, ended, "meeting.ended", operation.correlationId(), evidence);
        operations.succeed(operation, OffsetDateTime.now(clock));
        ParticipantRole viewerRole = meetings.participant(
                        subject.tenantId(), ended.meetingId(), subject.userId())
                .map(Participant::participantRole).orElse(ParticipantRole.ATTENDEE);
        return new Result(meetings.detail(ended), viewerRole);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failProvider(MediaOperation requested) {
        operations.failProvider(requested, OffsetDateTime.now(clock));
    }

    private MediaOperation prepareOperation(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            OperationType type,
            long expectedVersion,
            String idempotencyKey,
            String correlationId,
            MeetingMediaProvider.PreparedRoom room) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String hash = requestHash(
                type, meeting.meetingId(), expectedVersion, room.provider(), room.roomName());
        MediaOperation stored = operations.commandForUpdate(
                        subject.tenantId(), meeting.meetingId(), subject.userId(),
                        type, idempotencyKey)
                .orElse(null);
        if (stored != null) {
            requireMatchingRequest(stored, hash);
            if (stored.operationState() == OperationState.SUCCEEDED) {
                throw conflict("The completed lifecycle command has inconsistent meeting state.");
            }
            if (stored.operationState() == OperationState.RUNNING
                    && stored.leaseExpiresAt().isAfter(now)) {
                throw conflict("A matching meeting media operation is still in progress.");
            }
            UUID fence = UUID.randomUUID();
            return operations.reclaim(
                            stored, fence, now,
                            now.plus(mediaProperties.getLifecycleOperationLease()))
                    .orElseThrow(() -> conflict(
                            "The meeting media operation was reclaimed by another worker."));
        }
        MediaOperation active = operations.activeForUpdate(
                subject.tenantId(), meeting.meetingId(), type).orElse(null);
        if (active != null) {
            if (active.leaseExpiresAt().isAfter(now)) {
                throw conflict("Another meeting media operation is still in progress.");
            }
            if (!operations.expireActive(active, now)) {
                throw conflict("Another worker reclaimed the meeting media operation.");
            }
        }
        MediaOperation created = new MediaOperation(
                UUID.randomUUID(), subject.tenantId(), meeting.meetingId(), type,
                OperationState.RUNNING, subject.userId(), expectedVersion,
                idempotencyKey, hash, correlationId, UUID.randomUUID(),
                now.plus(mediaProperties.getLifecycleOperationLease()), 1,
                room.provider(), room.roomName(), room.incarnation());
        return operations.insert(created, now)
                .orElseThrow(() -> conflict("Another meeting media operation already exists."));
    }

    private MediaOperation currentOperation(
            MediaOperation requested, OperationType expectedType) {
        MediaOperation current = operations.commandForUpdate(
                        requested.tenantId(), requested.meetingId(), requested.actorUserId(),
                        requested.operationType(), requested.idempotencyKey())
                .orElseThrow(() -> conflict("The meeting media operation was not found."));
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (current.operationType() != expectedType
                || current.operationState() != OperationState.RUNNING
                || !current.executionFence().equals(requested.executionFence())
                || !current.leaseExpiresAt().isAfter(now)) {
            throw conflict("The meeting media operation lease changed or expired.");
        }
        return current;
    }

    private Participant requireHost(
            MeetingRequestContext.Subject subject, Meeting meeting) {
        Participant participant = meetings.participant(
                        subject.tenantId(), meeting.meetingId(), subject.userId())
                .orElseThrow(() -> forbidden("A meeting host role is required."));
        if (!participant.canHost()) throw forbidden("A meeting host role is required.");
        return participant;
    }

    private TenantPolicy requireEnabledPolicy(MeetingRequestContext.Subject subject) {
        TenantPolicy policy = meetings.ensurePolicy(subject.tenantId(), subject.userId());
        if (!policy.meetingsEnabled()) {
            throw forbidden("Meetings are disabled for this tenant.");
        }
        return policy;
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

    private void validateRoomPlan(
            MeetingMediaProvider.Capability capability,
            MeetingMediaProvider.PreparedRoom room) {
        if (room == null || room.provider() == null || room.roomName() == null
                || room.provider().isBlank() || room.roomName().isBlank()
                || room.incarnation() == null || room.meetingId() == null
                || room.tenantId() <= 0
                || !capability.provider().equals(room.provider())) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The realtime provider returned an invalid room plan.");
        }
    }

    @Transactional(readOnly = true)
    public int maximumParticipants(long tenantId) {
        return meetings.policy(tenantId)
                .orElseThrow(() -> forbidden(
                        "The tenant meeting policy is unavailable for recovery."))
                .maximumParticipants();
    }

    private UUID incarnation(long tenantId, UUID meetingId, String idempotencyKey) {
        String material = "dwp-meeting-media-v1|" + tenantId + "|" + meetingId
                + "|" + idempotencyKey;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    private void requireMatchingRequest(MediaOperation operation, String hash) {
        if (!requestHashesMatch(operation.requestSha256(), hash)) {
            throw conflict("The idempotency key was used for a different lifecycle request.");
        }
    }

    private void requireVersion(Meeting meeting, long expectedVersion) {
        if (meeting.version() != expectedVersion) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The meeting version changed. Refresh and retry.");
        }
    }

    private BaseException invalidState(String message) {
        return new BaseException(ErrorCode.INVALID_STATE, message);
    }

    private BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
