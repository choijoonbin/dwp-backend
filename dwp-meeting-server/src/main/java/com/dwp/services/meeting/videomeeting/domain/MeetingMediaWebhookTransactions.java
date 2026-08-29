package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingMediaWebhookRepository.CleanupClaim;
import com.dwp.services.meeting.videomeeting.domain.MeetingMediaWebhookRepository.MeetingBinding;
import com.dwp.services.meeting.videomeeting.domain.MeetingMediaWebhookRepository.ParticipantBinding;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaWebhook.EventType;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaWebhook.ProviderEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
class MeetingMediaWebhookTransactions {

    private final MeetingMediaWebhookRepository webhooks;
    private final VideoMeetingRepository meetings;
    private final VideoMeetingAuditRecorder audit;
    private final MeetingMediaProperties mediaProperties;
    private final MeetingLifecycleRecoveryProperties recoveryProperties;
    private final Clock clock;

    @Autowired
    MeetingMediaWebhookTransactions(
            MeetingMediaWebhookRepository webhooks,
            VideoMeetingRepository meetings,
            VideoMeetingAuditRecorder audit,
            MeetingMediaProperties mediaProperties,
            MeetingLifecycleRecoveryProperties recoveryProperties) {
        this(webhooks, meetings, audit, mediaProperties, recoveryProperties,
                Clock.systemUTC());
    }

    MeetingMediaWebhookTransactions(
            MeetingMediaWebhookRepository webhooks,
            VideoMeetingRepository meetings,
            VideoMeetingAuditRecorder audit,
            MeetingMediaProperties mediaProperties,
            MeetingLifecycleRecoveryProperties recoveryProperties,
            Clock clock) {
        this.webhooks = webhooks;
        this.meetings = meetings;
        this.audit = audit;
        this.mediaProperties = mediaProperties;
        this.recoveryProperties = recoveryProperties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ApplyResult apply(ProviderEvent event) {
        if (!webhooks.reserve(event, OffsetDateTime.now(clock))) {
            return ApplyResult.duplicateResult();
        }
        MeetingBinding binding = webhooks.meetingForUpdate(event).orElse(null);
        if (binding == null || !exactRoom(event, binding)) {
            webhooks.complete(event, "IGNORED", "ROOM_BINDING_MISMATCH");
            return ApplyResult.ignoredResult();
        }
        return switch (event.type()) {
            case ROOM_STARTED -> roomStarted(event, binding);
            case ROOM_FINISHED -> roomFinished(event, binding);
            case PARTICIPANT_JOINED -> participantJoined(event, binding);
            case PARTICIPANT_LEFT -> participantLeft(event, binding, false);
            case PARTICIPANT_CONNECTION_ABORTED -> participantLeft(event, binding, true);
        };
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<CleanupClaim> claimCleanup() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return webhooks.claimCleanup(
                UUID.randomUUID(), now,
                now.plus(mediaProperties.getLifecycleOperationLease()),
                recoveryProperties.getMaximumAttempts());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cleanupSucceeded(CleanupClaim claim) {
        webhooks.completeCleanup(claim, OffsetDateTime.now(clock));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cleanupFailed(CleanupClaim claim) {
        OffsetDateTime failedAt = OffsetDateTime.now(clock);
        webhooks.failCleanup(
                claim, failedAt, failedAt.plus(recoveryProperties.getRetryDelay()));
    }

    private ApplyResult roomStarted(ProviderEvent event, MeetingBinding binding) {
        if ("ENDED".equals(binding.lifecycleState())
                || "ENDED".equals(binding.mediaAccessState())) {
            webhooks.complete(event, "CLEANUP_REQUIRED", "MEDIA_SESSION_REVOKED");
            return ApplyResult.cleanupResult(event.room().roomName());
        }
        if (!"LIVE".equals(binding.lifecycleState())
                || !("ACTIVE".equals(binding.mediaAccessState())
                || "ENDING".equals(binding.mediaAccessState()))) {
            webhooks.complete(event, "IGNORED", "MEDIA_SESSION_NOT_ACTIVE");
            return ApplyResult.ignoredResult();
        }
        webhooks.observeRoomStarted(event);
        webhooks.complete(event, "APPLIED", null);
        return ApplyResult.appliedResult();
    }

    private ApplyResult roomFinished(ProviderEvent event, MeetingBinding binding) {
        if (!"LIVE".equals(binding.lifecycleState())) {
            webhooks.complete(event, "IGNORED", "MEETING_ALREADY_TERMINAL");
            return ApplyResult.ignoredResult();
        }
        boolean ended = webhooks.roomFinished(event, binding);
        Meeting meeting = meetings.lockMeeting(binding.tenantId(), binding.meetingId());
        if (ended) {
            meetings.recordEvent(
                    meeting, null, null, "ENDED", event.eventId(), event.eventId(),
                    evidence(event));
            audit.providerLifecycle(
                    binding.tenantId(), meeting, "meeting.provider-room.finished",
                    event.eventId(), evidence(event));
        }
        webhooks.complete(event, "APPLIED", null);
        return ApplyResult.appliedResult();
    }

    private ApplyResult participantJoined(ProviderEvent event, MeetingBinding binding) {
        ParticipantBinding participant = exactParticipant(event).orElse(null);
        if (participant == null) {
            webhooks.complete(event, "IGNORED", "PARTICIPANT_BINDING_MISMATCH");
            return ApplyResult.ignoredResult();
        }
        if (!"LIVE".equals(binding.lifecycleState())
                || !"ACTIVE".equals(binding.mediaAccessState())) {
            webhooks.complete(event, "CLEANUP_REQUIRED", "MEDIA_SESSION_REVOKED");
            return ApplyResult.cleanupResult(event.room().roomName());
        }
        boolean changed = webhooks.participantJoined(event);
        if (changed) participantAudit(event, binding, "JOINED", "meeting.provider.joined");
        webhooks.complete(event, "APPLIED", null);
        return ApplyResult.appliedResult();
    }

    private ApplyResult participantLeft(
            ProviderEvent event, MeetingBinding binding, boolean aborted) {
        ParticipantBinding participant = exactParticipant(event).orElse(null);
        if (participant == null) {
            webhooks.complete(event, "IGNORED", "PARTICIPANT_BINDING_MISMATCH");
            return ApplyResult.ignoredResult();
        }
        boolean changed = webhooks.participantLeft(event, aborted);
        if (changed) {
            participantAudit(
                    event, binding, "LEFT",
                    aborted ? "meeting.provider.connection-aborted"
                            : "meeting.provider.left");
        }
        webhooks.complete(event, "APPLIED", null);
        return ApplyResult.appliedResult();
    }

    private Optional<ParticipantBinding> exactParticipant(ProviderEvent event) {
        if (event.participant() == null) return Optional.empty();
        return webhooks.participant(event).filter(stored ->
                !stored.userIdNull()
                        && stored.participantId().equals(event.participant().participantId())
                        && stored.userId() == event.participant().userId()
                        && event.participant().identity().equals(identity(event)));
    }

    private boolean exactRoom(ProviderEvent event, MeetingBinding binding) {
        return event.provider().equals(binding.provider())
                && event.room().roomName().equals(binding.roomName())
                && event.room().incarnation().equals(binding.incarnation())
                && event.room().tenantId() == binding.tenantId()
                && event.room().meetingId().equals(binding.meetingId());
    }

    private String identity(ProviderEvent event) {
        var participant = event.participant();
        return "tenant:" + event.room().tenantId()
                + ":meeting:" + event.room().meetingId()
                + ":participant:" + participant.participantId()
                + ":incarnation:" + event.room().incarnation()
                + ":user:" + participant.userId();
    }

    private void participantAudit(
            ProviderEvent event,
            MeetingBinding binding,
            String eventType,
            String action) {
        Meeting meeting = meetings.lockMeeting(binding.tenantId(), binding.meetingId());
        Participant participant = meetings.participant(
                        binding.tenantId(), binding.meetingId(),
                        event.participant().participantId())
                .orElseThrow();
        meetings.recordEvent(
                meeting, participant, null, eventType, event.eventId(), event.eventId(),
                evidence(event));
        audit.providerParticipant(
                binding.tenantId(), meeting, participant, action,
                event.eventId(), evidence(event));
    }

    private Map<String, Object> evidence(ProviderEvent event) {
        return Map.of(
                "provider", event.provider(),
                "providerEventId", event.eventId(),
                "providerEventType", event.type().name(),
                "roomIncarnation", event.room().incarnation().toString());
    }

    record ApplyResult(boolean duplicate, boolean cleanup, String roomName) {

        static ApplyResult duplicateResult() {
            return new ApplyResult(true, false, null);
        }

        static ApplyResult ignoredResult() {
            return new ApplyResult(false, false, null);
        }

        static ApplyResult appliedResult() {
            return new ApplyResult(false, false, null);
        }

        static ApplyResult cleanupResult(String roomName) {
            return new ApplyResult(false, true, roomName);
        }
    }
}
