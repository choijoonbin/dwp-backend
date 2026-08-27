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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.canonicalGuests;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.canonicalUserIds;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.commandKey;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.correlation;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.normalized;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.optional;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHash;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHashesMatch;

final class VideoMeetingCreationCoordinator {

    private final VideoMeetingRepository repository;
    private final MeetingJoinCodeGenerator joinCodeGenerator;
    private final VideoMeetingAuditRecorder audit;

    VideoMeetingCreationCoordinator(
            VideoMeetingRepository repository,
            MeetingJoinCodeGenerator joinCodeGenerator,
            VideoMeetingAuditRecorder audit) {
        this.repository = repository;
        this.joinCodeGenerator = joinCodeGenerator;
        this.audit = audit;
    }

    VideoMeetingDtos.MeetingCreatedResponse create(
            String title,
            String description,
            String agenda,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timeZone,
            AccessScope accessScope,
            Boolean waitingRoomEnabled,
            Boolean guestAccessEnabled,
            Boolean allowJoinBeforeHost,
            Boolean defaultMicrophoneEnabled,
            Boolean defaultCameraEnabled,
            List<Long> participantUserIds,
            List<VideoMeetingDtos.GuestInvitee> guestInvitees,
            LifecycleState initialState,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        TenantPolicy policy = requireEnabledPolicy(subject);
        String commandKey = commandKey(idempotencyKey);
        String requestHash = requestHash(
                initialState, title, description, agenda, startsAt, endsAt, timeZone,
                accessScope, waitingRoomEnabled, guestAccessEnabled, allowJoinBeforeHost,
                defaultMicrophoneEnabled, defaultCameraEnabled,
                canonicalUserIds(participantUserIds), canonicalGuests(guestInvitees));
        VideoMeetingRepository.IdempotentMeeting existing = repository.byIdempotency(
                subject.tenantId(), subject.userId(), commandKey).orElse(null);
        if (existing != null) return idempotentResult(existing, requestHash);

        rejectUnverifiedEntryOptions(
                accessScope, guestAccessEnabled, allowJoinBeforeHost, guestInvitees);
        int participantCount = 1 + canonicalUserIds(participantUserIds).size()
                + canonicalGuests(guestInvitees).size();
        if (participantCount > policy.maximumParticipants()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The tenant meeting participant limit was exceeded.");
        }
        PersonSnapshot organizer = repository.person(subject.tenantId(), subject.userId())
                .orElseThrow(() -> notFound(
                        "The organizer workforce projection was not found."));
        String eventCorrelation = correlation(correlationId);
        boolean guestsEnabled = Boolean.TRUE.equals(guestAccessEnabled);
        Meeting meeting;
        try {
            meeting = createMeeting(
                    subject, policy, organizer, title, description, agenda, startsAt, endsAt,
                    timeZone, accessScope, initialState, waitingRoomEnabled, guestsEnabled,
                    allowJoinBeforeHost, defaultMicrophoneEnabled, defaultCameraEnabled,
                    commandKey, requestHash, eventCorrelation);
        } catch (BaseException exception) {
            VideoMeetingRepository.IdempotentMeeting concurrent = repository.byIdempotency(
                    subject.tenantId(), subject.userId(), commandKey).orElse(null);
            if (concurrent != null) return idempotentResult(concurrent, requestHash);
            throw exception;
        }

        repository.addInternalParticipant(
                meeting, organizer, ParticipantRole.ORGANIZER,
                AttendanceState.ADMITTED, subject.userId());
        addInvitees(meeting, organizer, participantUserIds, guestInvitees, policy, subject.userId());
        recordCreation(
                subject, meeting, initialState, accessScope, participantCount,
                eventCorrelation, commandKey);
        MeetingDetail detail = repository.detail(meeting);
        return new VideoMeetingDtos.MeetingCreatedResponse(
                VideoMeetingDtos.MeetingDetailResponse.from(
                        detail, ParticipantRole.ORGANIZER),
                VideoMeetingDtos.meetingCode(meeting.joinCode()));
    }

    private Meeting createMeeting(
            MeetingRequestContext.Subject subject,
            TenantPolicy policy,
            PersonSnapshot organizer,
            String title,
            String description,
            String agenda,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timeZone,
            AccessScope accessScope,
            LifecycleState initialState,
            Boolean waitingRoomEnabled,
            boolean guestsEnabled,
            Boolean allowJoinBeforeHost,
            Boolean defaultMicrophoneEnabled,
            Boolean defaultCameraEnabled,
            String commandKey,
            String requestHash,
            String eventCorrelation) {
        return repository.create(new VideoMeetingRepository.CreateMeeting(
                UUID.randomUUID(), subject.tenantId(), normalized(title), optional(description),
                optional(agenda), initialState.name(),
                accessScope.name(), uniqueJoinCode(subject), startsAt, endsAt, timeZone,
                policy.waitingRoomRequired() || Boolean.TRUE.equals(waitingRoomEnabled),
                guestsEnabled,
                policy.allowJoinBeforeHost() && Boolean.TRUE.equals(allowJoinBeforeHost),
                Boolean.TRUE.equals(defaultMicrophoneEnabled),
                Boolean.TRUE.equals(defaultCameraEnabled),
                organizer, commandKey, requestHash, eventCorrelation));
    }

    private void addInvitees(
            Meeting meeting,
            PersonSnapshot organizer,
            List<Long> participantUserIds,
            List<VideoMeetingDtos.GuestInvitee> guestInvitees,
            TenantPolicy policy,
            long actorUserId) {
        LinkedHashSet<Long> requestedIds = new LinkedHashSet<>(
                participantUserIds == null ? List.of() : participantUserIds);
        requestedIds.remove(organizer.userId());
        List<PersonSnapshot> people = repository.people(
                meeting.tenantId(), new ArrayList<>(requestedIds));
        if (people.size() != requestedIds.size()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "One or more invited workforce members are unavailable.");
        }
        people.forEach(person -> repository.addInternalParticipant(
                meeting, person, ParticipantRole.ATTENDEE, AttendanceState.INVITED, actorUserId));
        List<VideoMeetingDtos.GuestInvitee> guests = guestInvitees == null
                ? List.of() : guestInvitees;
        if (!guests.isEmpty() && (!policy.guestsAllowed() || !meeting.guestAccessEnabled())) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Guest access is not enabled.");
        }
        guests.stream()
                .collect(java.util.stream.Collectors.toMap(
                        guest -> guest.emailAddress().trim().toLowerCase(Locale.ROOT),
                        guest -> guest,
                        (first, ignored) -> first,
                        java.util.LinkedHashMap::new))
                .values().forEach(guest -> repository.addGuestParticipant(
                        meeting, guest, actorUserId));
    }

    private void recordCreation(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            LifecycleState initialState,
            AccessScope accessScope,
            int participantCount,
            String eventCorrelation,
            String commandKey) {
        String eventType = initialState == LifecycleState.SCHEDULED ? "SCHEDULED" : "CREATED";
        Map<String, Object> evidence = Map.of(
                "accessScope", accessScope.name(),
                "meetingKind", "FORMAL_MEETING",
                "participantCount", participantCount,
                "waitingRoomEnabled", meeting.waitingRoomEnabled(),
                "guestAccessEnabled", meeting.guestAccessEnabled());
        repository.recordEvent(
                meeting, null, subject.userId(), eventType,
                eventCorrelation, commandKey, evidence);
        audit.meetingLifecycle(
                subject, meeting,
                initialState == LifecycleState.SCHEDULED
                        ? "meeting.scheduled" : "meeting.created",
                eventCorrelation, evidence);
    }

    private VideoMeetingDtos.MeetingCreatedResponse idempotentResult(
            VideoMeetingRepository.IdempotentMeeting existing, String requestHash) {
        Meeting meeting = idempotentMeeting(existing, requestHash);
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        ParticipantRole viewerRole = repository.participant(
                        subject.tenantId(), meeting.meetingId(), subject.userId())
                .map(Participant::participantRole).orElse(null);
        return new VideoMeetingDtos.MeetingCreatedResponse(
                VideoMeetingDtos.MeetingDetailResponse.from(
                        repository.detail(meeting), viewerRole),
                VideoMeetingDtos.meetingCode(meeting.joinCode()));
    }

    private Meeting idempotentMeeting(
            VideoMeetingRepository.IdempotentMeeting existing, String requestHash) {
        if (!requestHashesMatch(existing.requestHash(), requestHash)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key was already used for a different meeting request.");
        }
        return existing.meeting();
    }

    private TenantPolicy requireEnabledPolicy(MeetingRequestContext.Subject subject) {
        TenantPolicy policy = repository.ensurePolicy(subject.tenantId(), subject.userId());
        if (!policy.meetingsEnabled()) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Meetings are disabled for this tenant.");
        }
        return policy;
    }

    private void rejectUnverifiedEntryOptions(
            AccessScope accessScope,
            Boolean guestAccessEnabled,
            Boolean allowJoinBeforeHost,
            List<VideoMeetingDtos.GuestInvitee> guestInvitees) {
        boolean guestsRequested = guestInvitees != null && !guestInvitees.isEmpty();
        if (accessScope == AccessScope.PUBLIC_CODE || guestsRequested
                || Boolean.TRUE.equals(guestAccessEnabled)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "External meeting access is not available until guest identity verification is configured.");
        }
        if (Boolean.TRUE.equals(allowJoinBeforeHost)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Joining before the host is not available in this release.");
        }
    }

    private String uniqueJoinCode(MeetingRequestContext.Subject subject) {
        for (int attempt = 0; attempt < 12; attempt++) {
            String code = joinCodeGenerator.generate();
            if (!repository.joinCodeExists(subject.tenantId(), code)) return code;
        }
        throw new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "A unique meeting code could not be allocated.");
    }

    private BaseException notFound(String message) {
        return new BaseException(ErrorCode.ENTITY_NOT_FOUND, message);
    }
}
