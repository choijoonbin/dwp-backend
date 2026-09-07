package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingPreparationDtos.*;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class VideoMeetingPreparationService {
    private final VideoMeetingRepository meetings;
    private final VideoMeetingPreparationRepository preparation;
    private final VideoMeetingAuditRecorder audit;
    private final VideoMeetingScheduleRepository schedules;
    private final MeetingPreparationMaterialRetentionService materialRetention;

    @Autowired
    public VideoMeetingPreparationService(VideoMeetingRepository meetings,
            VideoMeetingPreparationRepository preparation, VideoMeetingAuditRecorder audit,
            VideoMeetingScheduleRepository schedules,
            MeetingPreparationMaterialRetentionService materialRetention) {
        this.meetings = meetings;
        this.preparation = preparation;
        this.audit = audit;
        this.schedules = schedules;
        this.materialRetention = materialRetention;
    }

    VideoMeetingPreparationService(VideoMeetingRepository meetings,
            VideoMeetingPreparationRepository preparation, VideoMeetingAuditRecorder audit) {
        this(meetings, preparation, audit, null, null);
    }

    VideoMeetingPreparationService(VideoMeetingRepository meetings,
            VideoMeetingPreparationRepository preparation, VideoMeetingAuditRecorder audit,
            VideoMeetingScheduleRepository schedules) {
        this(meetings, preparation, audit, schedules, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PreparationResponse read(UUID meetingId) {
        var subject = subject();
        Meeting meeting = accessible(subject, meetingId, false);
        return projection(subject, meeting);
    }

    @Transactional
    public PreparationResponse replaceAgenda(UUID meetingId, ReplaceAgendaRequest request,
            String idempotencyKey, String correlationId) {
        var subject = subject();
        Meeting meeting = accessible(subject, meetingId, true);
        if (!host(subject, meeting) || !canUpdate(subject)) throw forbidden();
        if (request == null || request.expectedAgendaVersion() == null
                || request.expectedAgendaVersion() < 0 || request.items() == null)
            throw VideoMeetingPreparationPolicy.invalid();
        List<AgendaItemInput> items = VideoMeetingPreparationPolicy.canonicalItems(request.items());
        String key = VideoMeetingCommandPolicy.commandKey(idempotencyKey);
        String digest = VideoMeetingCommandPolicy.requestHash(request.expectedAgendaVersion(),
                VideoMeetingPreparationPolicy.fingerprint(items));
        if (preparation.replay(meeting, subject.userId(), "AGENDA_REPLACE", key, digest).isPresent())
            return projection(subject, meeting);
        requirePreparatory(meeting);
        long version = preparation.replaceAgenda(meeting, request.expectedAgendaVersion(), items, subject.userId());
        audit.collaboration(subject, meeting, "meeting.agenda.updated", "MEETING_AGENDA",
                meetingId.toString(), VideoMeetingCommandPolicy.correlation(correlationId), false,
                Map.of("agendaVersion", version, "itemCount", items.size()));
        preparation.complete(meeting, subject.userId(), "AGENDA_REPLACE", key, digest, version);
        return projection(subject, meeting);
    }

    @Transactional
    public PreparationResponse respond(UUID meetingId, InvitationResponseRequest request,
            String idempotencyKey, String correlationId) {
        var subject = subject();
        Meeting meeting = accessible(subject, meetingId, true);
        Participant participant = participant(subject, meeting);
        boolean invited = preparation.responses(meeting, subject.userId()).stream().anyMatch(InvitationResponse::mine);
        if (!invited || participant == null || participant.participantRole() == ParticipantRole.ORGANIZER)
            throw forbidden();
        if (request == null || request.expectedInvitationRevision() == null
                || request.expectedInvitationRevision() < 1 || request.expectedVersion() == null
                || request.expectedVersion() < 0 || request.response() == null
                || !Set.of("ACCEPTED", "TENTATIVE", "DECLINED").contains(request.response()))
            throw VideoMeetingPreparationPolicy.invalid();
        String key = VideoMeetingCommandPolicy.commandKey(idempotencyKey);
        String digest = VideoMeetingCommandPolicy.requestHash(request.expectedInvitationRevision(),
                request.expectedVersion(), request.response());
        if (preparation.replay(meeting, subject.userId(), "INVITATION_RESPOND", key, digest).isPresent())
            return projection(subject, meeting);
        requirePreparatory(meeting);
        if (preparation.versions(meeting).invitationRevision() != request.expectedInvitationRevision())
            throw VideoMeetingPreparationPolicy.conflict();
        long version = preparation.respond(meeting, participant.participantId(),
                request.expectedInvitationRevision(), request.expectedVersion(), request.response());
        audit.collaboration(subject, meeting, "meeting.invitation.responded", "MEETING_INVITATION",
                participant.participantId().toString(), VideoMeetingCommandPolicy.correlation(correlationId), false,
                Map.of("invitationRevision", request.expectedInvitationRevision(), "responseVersion", version));
        preparation.complete(meeting, subject.userId(), "INVITATION_RESPOND", key, digest, version);
        return projection(subject, meeting);
    }

    @Transactional
    public PreparationResponse updateMyPreparation(
            UUID meetingId,
            UpdateMyPreparationRequest request,
            String idempotencyKey,
            String correlationId) {
        var subject = subject();
        Meeting meeting = accessible(subject, meetingId, true);
        Participant participant = participant(subject, meeting);
        if (participant == null || !preparation.activeInternalParticipant(
                meeting, subject.userId())) throw forbidden();
        if (request == null || request.expectedAgendaVersion() == null
                || request.expectedAgendaVersion() < 0 || request.expectedVersion() == null
                || request.expectedVersion() < 0 || request.preparedAgendaItemIds() == null
                || request.preparedAgendaItemIds().size() > 50
                || request.preparedAgendaItemIds().stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(request.preparedAgendaItemIds()).size()
                    != request.preparedAgendaItemIds().size()) {
            throw VideoMeetingPreparationPolicy.invalid();
        }
        List<UUID> preparedIds = request.preparedAgendaItemIds().stream()
                .sorted().toList();
        String key = VideoMeetingCommandPolicy.commandKey(idempotencyKey);
        String digest = VideoMeetingCommandPolicy.requestHash(
                request.expectedAgendaVersion(), request.expectedVersion(), preparedIds);
        if (preparation.replay(meeting, subject.userId(), "PREPARATION_CHECK", key, digest)
                .isPresent()) return projection(subject, meeting);
        requirePreparatory(meeting);
        long version = preparation.replaceMyPreparation(
                meeting, participant.participantId(), request.expectedAgendaVersion(),
                request.expectedVersion(), preparedIds);
        audit.collaboration(subject, meeting, "meeting.personal-preparation.updated",
                "MEETING_PERSONAL_PREPARATION", participant.participantId().toString(),
                VideoMeetingCommandPolicy.correlation(correlationId), false,
                Map.of("agendaVersion", request.expectedAgendaVersion(),
                        "preparationVersion", version));
        preparation.complete(meeting, subject.userId(), "PREPARATION_CHECK",
                key, digest, version);
        return projection(subject, meeting);
    }

    @Transactional
    public PreparationResponse registerMaterial(UUID meetingId, RegisterMaterialRequest request,
            String idempotencyKey, String correlationId) {
        var subject = subject();
        Meeting meeting = accessible(subject, meetingId, true);
        if (!host(subject, meeting) || !canUpdate(subject)) throw forbidden();
        validateMaterial(request);
        String key = VideoMeetingCommandPolicy.commandKey(idempotencyKey);
        String digest = VideoMeetingCommandPolicy.requestHash(
                request.displayName().trim(), request.contentType().trim().toLowerCase(java.util.Locale.ROOT),
                request.referenceProvider(), request.opaqueReference(), request.sourceVersion(),
                request.classification(), request.sizeBytes(), request.contentSha256(),
                request.expectedMaterialsVersion());
        if (preparation.replay(meeting, subject.userId(), "MATERIAL_REGISTER", key, digest).isPresent())
            return projection(subject, meeting);
        requirePreparatory(meeting);
        if (materialRetention == null || !materialRetention.ready())
            throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Meeting preparation material retention is not ready.");
        int retentionDays = meetings.ensurePolicy(
                subject.tenantId(), subject.userId()).artifactRetentionDays();
        long version = preparation.registerMaterial(meeting, request, retentionDays, subject.userId());
        audit.collaboration(subject, meeting, "meeting.preparation-material.registered",
                "MEETING_PREPARATION", meetingId.toString(),
                VideoMeetingCommandPolicy.correlation(correlationId), false,
                Map.of("materialsVersion", version,
                        "classification", request.classification(),
                        "accessVerificationState", "PENDING_REVALIDATION"));
        if (schedules != null)
            schedules.recordInvitationEvent(meeting, "PREPARATION_MATERIAL_ADDED", version);
        preparation.complete(meeting, subject.userId(), "MATERIAL_REGISTER", key, digest, version);
        return projection(subject, meeting);
    }

    @Transactional
    public PreparationResponse removeMaterial(UUID meetingId, UUID materialId,
            RemoveMaterialRequest request, String idempotencyKey, String correlationId) {
        var subject = subject();
        Meeting meeting = accessible(subject, meetingId, true);
        if (!host(subject, meeting) || !canUpdate(subject)) throw forbidden();
        if (materialId == null || request == null || request.expectedMaterialsVersion() == null
                || request.expectedMaterialsVersion() < 0 || request.expectedVersion() == null
                || request.expectedVersion() < 0) throw VideoMeetingPreparationPolicy.invalid();
        String key = VideoMeetingCommandPolicy.commandKey(idempotencyKey);
        String digest = VideoMeetingCommandPolicy.requestHash(materialId,
                request.expectedMaterialsVersion(), request.expectedVersion());
        if (preparation.replay(meeting, subject.userId(), "MATERIAL_REMOVE", key, digest).isPresent())
            return projection(subject, meeting);
        requirePreparatory(meeting);
        long version = preparation.removeMaterial(meeting, materialId,
                request.expectedMaterialsVersion(), request.expectedVersion(), subject.userId());
        audit.collaboration(subject, meeting, "meeting.preparation-material.removed",
                "MEETING_PREPARATION_MATERIAL", materialId.toString(),
                VideoMeetingCommandPolicy.correlation(correlationId), false,
                Map.of("materialsVersion", version));
        if (schedules != null)
            schedules.recordInvitationEvent(meeting, "PREPARATION_MATERIAL_REMOVED", version);
        preparation.complete(meeting, subject.userId(), "MATERIAL_REMOVE", key, digest, version);
        return projection(subject, meeting);
    }

    private PreparationResponse projection(MeetingRequestContext.Subject subject, Meeting meeting) {
        var versions = preparation.versions(meeting);
        var responses = preparation.responses(meeting, subject.userId());
        var mine = responses.stream().filter(InvitationResponse::mine).findFirst().orElse(null);
        boolean host = host(subject, meeting);
        int accepted = 0, tentative = 0, declined = 0, pending = 0;
        for (InvitationResponse response : responses) {
            switch (response.response()) {
                case "ACCEPTED" -> accepted++;
                case "TENTATIVE" -> tentative++;
                case "DECLINED" -> declined++;
                default -> pending++;
            }
        }
        Participant viewer = participant(subject, meeting);
        boolean canPrepare = preparatory(meeting) && viewer != null
                && preparation.activeInternalParticipant(meeting, subject.userId());
        MyPreparationResponse myPreparation = viewer == null
                ? new MyPreparationResponse(versions.agendaVersion(), 0, List.of(), null)
                : preparation.myPreparation(
                        meeting, viewer.participantId(), versions.agendaVersion());
        return new PreparationResponse(meeting.meetingId(), meeting.version(),
                versions.agendaVersion(), versions.materialsVersion(), versions.invitationRevision(),
                preparation.agenda(meeting), preparation.materials(meeting, host),
                mine, host ? responses : mine == null ? List.of() : List.of(mine),
                new InvitationCounts(accepted, tentative, declined, pending), myPreparation,
                preparatory(meeting) && host && canUpdate(subject),
                preparatory(meeting) && host && canUpdate(subject),
                preparatory(meeting) && mine != null && viewer != null && viewer.participantRole() != ParticipantRole.ORGANIZER,
                canPrepare,
                OffsetDateTime.now());
    }

    private MeetingRequestContext.Subject subject() {
        var subject = MeetingRequestContext.get();
        if (!subject.permissions().contains("APP.MEETINGS:VIEW")) throw forbidden();
        return subject;
    }

    private Meeting accessible(MeetingRequestContext.Subject subject, UUID meetingId, boolean lock) {
        var candidate = lock ? meetings.lockAccessibleMeeting(subject.tenantId(), meetingId, subject.userId())
                : meetings.accessibleMeeting(subject.tenantId(), meetingId, subject.userId());
        return candidate.orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND,
                "The meeting preparation was not found."));
    }

    private Participant participant(MeetingRequestContext.Subject subject, Meeting meeting) {
        return meetings.participant(subject.tenantId(), meeting.meetingId(), subject.userId())
                .filter(person -> person.attendanceState() != VideoMeetingModels.AttendanceState.DENIED)
                .orElse(null);
    }

    private boolean host(MeetingRequestContext.Subject subject, Meeting meeting) {
        var viewer = participant(subject, meeting);
        return meeting.organizerUserId() == subject.userId() || viewer != null && viewer.canHost();
    }

    private boolean canUpdate(MeetingRequestContext.Subject subject) {
        return subject.permissions().contains("APP.MEETINGS:UPDATE")
                || subject.permissions().contains("APP.MEETINGS:MANAGE");
    }

    private boolean preparatory(Meeting meeting) {
        return Set.of(VideoMeetingModels.LifecycleState.DRAFT, VideoMeetingModels.LifecycleState.SCHEDULED,
                VideoMeetingModels.LifecycleState.LOBBY).contains(meeting.lifecycleState());
    }

    private void requirePreparatory(Meeting meeting) {
        if (!preparatory(meeting)) throw VideoMeetingPreparationPolicy.conflict();
    }

    private void validateMaterial(RegisterMaterialRequest request) {
        if (request == null || request.expectedMaterialsVersion() == null
                || request.expectedMaterialsVersion() < 0
                || request.displayName() == null || request.displayName().trim().isEmpty()
                || request.contentType() == null
                || !request.contentType().trim().toLowerCase(java.util.Locale.ROOT)
                        .matches("^[a-z0-9][a-z0-9.+-]{0,63}/[a-z0-9][a-z0-9.+-]{0,63}$")
                || request.referenceProvider() == null
                || !Set.of("DWP_FILES", "SHAREPOINT", "CONFLUENCE")
                        .contains(request.referenceProvider())
                || request.opaqueReference() == null
                || !request.opaqueReference().matches("^[A-Za-z0-9][A-Za-z0-9._/-]{0,159}$")
                || request.sourceVersion() != null
                        && !request.sourceVersion().matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$")
                || request.classification() == null
                || !Set.of("INTERNAL", "CONFIDENTIAL", "RESTRICTED")
                        .contains(request.classification())
                || request.sizeBytes() != null
                        && (request.sizeBytes() < 0 || request.sizeBytes() > 10_737_418_240L)
                || request.contentSha256() != null
                        && !request.contentSha256().matches("^[0-9a-f]{64}$"))
            throw VideoMeetingPreparationPolicy.invalid();
    }

    private BaseException forbidden() {
        return new BaseException(ErrorCode.FORBIDDEN, "Meeting preparation authority is required.");
    }
}
