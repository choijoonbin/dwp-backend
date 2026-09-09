package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingScheduleDraftDtos.CommitScheduleDraftRequest;
import com.dwp.services.meeting.videomeeting.api.MeetingScheduleDraftDtos.DiscardScheduleDraftResponse;
import com.dwp.services.meeting.videomeeting.api.MeetingScheduleDraftDtos.DraftAgendaItem;
import com.dwp.services.meeting.videomeeting.api.MeetingScheduleDraftDtos.DraftRecurrence;
import com.dwp.services.meeting.videomeeting.api.MeetingScheduleDraftDtos.DraftVersionRequest;
import com.dwp.services.meeting.videomeeting.api.MeetingScheduleDraftDtos.SaveScheduleDraftRequest;
import com.dwp.services.meeting.videomeeting.api.MeetingScheduleDraftDtos.ScheduleDraftResponse;
import com.dwp.services.meeting.videomeeting.api.MeetingScheduleDraftDtos.ScheduleDraftSlotResponse;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingPreparationDtos.AgendaItemInput;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingScheduleDtos.CreateSeriesRequest;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingScheduleDtos.RecurrenceRequest;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingScheduleDtos.SeriesPreviewRequest;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingScheduleDtos.SeriesPreviewResponse;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingScheduleDraftRepository.StoredDraft;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AccessScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MeetingScheduleDraftService {

    private static final int MAX_RETENTION_DAYS = 7;

    private final MeetingScheduleDraftRepository drafts;
    private final MeetingScheduleDraftRetentionService retention;
    private final MeetingTemplateRepository templates;
    private final VideoMeetingRepository meetingRepository;
    private final VideoMeetingService meetings;
    private final VideoMeetingScheduleService schedules;
    private final VideoMeetingAuditRecorder audit;
    private final Clock clock;

    @Autowired
    public MeetingScheduleDraftService(
            MeetingScheduleDraftRepository drafts,
            MeetingScheduleDraftRetentionService retention,
            MeetingTemplateRepository templates,
            VideoMeetingRepository meetingRepository,
            VideoMeetingService meetings,
            VideoMeetingScheduleService schedules,
            VideoMeetingAuditRecorder audit) {
        this(drafts, retention, templates, meetingRepository, meetings, schedules,
                audit, Clock.systemUTC());
    }

    MeetingScheduleDraftService(
            MeetingScheduleDraftRepository drafts,
            MeetingScheduleDraftRetentionService retention,
            MeetingTemplateRepository templates,
            VideoMeetingRepository meetingRepository,
            VideoMeetingService meetings,
            VideoMeetingScheduleService schedules,
            VideoMeetingAuditRecorder audit,
            Clock clock) {
        this.drafts = drafts;
        this.retention = retention;
        this.templates = templates;
        this.meetingRepository = meetingRepository;
        this.meetings = meetings;
        this.schedules = schedules;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ScheduleDraftSlotResponse read() {
        MeetingRequestContext.Subject subject = subject();
        OffsetDateTime now = OffsetDateTime.now(clock);
        StoredDraft draft = drafts.find(subject.tenantId(), subject.userId(), false)
                .filter(candidate -> candidate.retentionUntil().isAfter(now))
                .orElse(null);
        if (draft == null) return new ScheduleDraftSlotResponse(
                null, false, null, null, null, now);
        if (!sourceAccessible(subject, draft)) {
            return new ScheduleDraftSlotResponse(
                    null, true, draft.draftId(), draft.version(), draft.retentionUntil(), now);
        }
        ScheduleDraftResponse response = drafts.response(draft);
        return new ScheduleDraftSlotResponse(response, false, draft.draftId(),
                draft.version(), draft.retentionUntil(), now);
    }

    @Transactional
    public ScheduleDraftResponse save(
            SaveScheduleDraftRequest supplied,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = subject();
        if (!retention.ready()) throw unavailable();
        var policy = meetingRepository.ensurePolicy(subject.tenantId(), subject.userId());
        if (!policy.meetingsEnabled()) throw forbidden();
        SaveScheduleDraftRequest request = canonical(supplied);
        validatePartial(subject, request);
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime retentionUntil = now.plusDays(
                Math.min(MAX_RETENTION_DAYS, policy.retentionDays()));

        drafts.lockOwner(subject.tenantId(), subject.userId());
        var command = drafts.beginCommand(subject.tenantId(), subject.userId(),
                "DRAFT_SAVE", idempotencyKey, request, now);
        if (command.replay() != null) {
            StoredDraft replay = drafts.find(subject.tenantId(), subject.userId(), false)
                    .filter(candidate -> candidate.draftId().equals(command.replay().resultId())
                            && candidate.version() == command.replay().resultVersion())
                    .orElseThrow(MeetingScheduleDraftService::conflict);
            validateSource(subject, replay);
            return drafts.response(replay);
        }

        StoredDraft current = drafts.find(subject.tenantId(), subject.userId(), true).orElse(null);
        StoredDraft saved;
        if (current == null) {
            if (request.expectedVersion() != null) throw conflict();
            saved = drafts.create(subject.tenantId(), subject.userId(), request, retentionUntil);
        } else if (!current.retentionUntil().isAfter(now)) {
            validateSource(subject, current);
            if (request.expectedVersion() != null) throw conflict();
            drafts.removeMutableReceipts(subject.tenantId(), subject.userId());
            saved = drafts.replaceExpired(current, request, retentionUntil);
        } else {
            validateSource(subject, current);
            if (request.expectedVersion() == null
                    || request.expectedVersion() != current.version()) throw conflict();
            saved = drafts.update(current, request, retentionUntil);
        }
        audit.workspaceChanged(subject, "meeting.schedule-draft.saved", "MEETING_SCHEDULE_DRAFT",
                saved.draftId().toString(), VideoMeetingCommandPolicy.correlation(correlationId),
                Map.of("version", saved.version(),
                        "participantCount", size(request.participantUserIds()),
                        "agendaItemCount", size(request.agendaItems()),
                        "recurring", request.recurrence() != null
                                && !"NONE".equals(request.recurrence().frequency())));
        drafts.completeCommand(subject.tenantId(), subject.userId(), command, "DRAFT",
                saved.draftId(), saved.version(), retentionUntil);
        return drafts.response(saved);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SeriesPreviewResponse preview(DraftVersionRequest request) {
        MeetingRequestContext.Subject subject = subject();
        StoredDraft draft = activeDraft(subject, false);
        expected(draft, request == null ? null : request.expectedVersion());
        validateSource(subject, draft);
        SaveScheduleDraftRequest content = content(draft);
        VideoMeetingDtos.ScheduleMeetingRequest meeting = strictMeeting(subject, content);
        DraftRecurrence recurrence = requiredRecurring(content.recurrence());
        return schedules.previewSeries(new SeriesPreviewRequest(meeting,
                new RecurrenceRequest(recurrence.frequency(), recurrence.interval(),
                        recurrence.occurrenceCount())));
    }

    @Transactional
    public VideoMeetingDtos.MeetingCreatedResponse commit(
            CommitScheduleDraftRequest request,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = subject();
        if (request == null || request.expectedVersion() == null
                || request.expectedVersion() < 0) throw invalid();
        OffsetDateTime now = OffsetDateTime.now(clock);
        drafts.lockOwner(subject.tenantId(), subject.userId());
        var command = drafts.beginCommand(subject.tenantId(), subject.userId(),
                "DRAFT_COMMIT", idempotencyKey, request, now);
        if (command.replay() != null) return created(command.replay().resultId());

        StoredDraft draft = activeDraft(subject, true);
        expected(draft, request.expectedVersion());
        validateSource(subject, draft);
        SaveScheduleDraftRequest content = content(draft);
        VideoMeetingDtos.ScheduleMeetingRequest meeting = strictMeeting(subject, content);
        String meetingKey = derivedMeetingKey(subject, draft, command.idempotencyKey());
        VideoMeetingDtos.MeetingCreatedResponse result;
        DraftRecurrence recurrence = content.recurrence();
        if (recurrence == null || "NONE".equals(recurrence.frequency())) {
            if (request.previewFingerprint() != null) throw invalid();
            result = meetings.schedule(meeting, meetingKey, correlationId);
        } else {
            if (request.previewFingerprint() == null) throw conflict();
            result = schedules.createSeries(new CreateSeriesRequest(
                    meeting,
                    new RecurrenceRequest(recurrence.frequency(), recurrence.interval(),
                            recurrence.occurrenceCount()),
                    request.previewFingerprint()), meetingKey, correlationId);
        }
        drafts.delete(draft);
        drafts.removeMutableReceipts(subject.tenantId(), subject.userId());
        OffsetDateTime receiptUntil = now.plusDays(MAX_RETENTION_DAYS);
        drafts.completeCommand(subject.tenantId(), subject.userId(), command, "MEETING",
                result.meeting().meetingId(), result.meeting().version(), receiptUntil);
        audit.workspaceChanged(subject, "meeting.schedule-draft.committed",
                "MEETING_SCHEDULE_DRAFT", draft.draftId().toString(),
                VideoMeetingCommandPolicy.correlation(correlationId),
                Map.of("draftVersion", draft.version(),
                        "meetingId", result.meeting().meetingId().toString(),
                        "meetingVersion", result.meeting().version(),
                        "recurring", recurrence != null
                                && !"NONE".equals(recurrence.frequency())));
        return result;
    }

    @Transactional
    public DiscardScheduleDraftResponse discard(
            DraftVersionRequest request,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = subject();
        if (request == null || request.expectedVersion() == null
                || request.expectedVersion() < 0) throw invalid();
        OffsetDateTime now = OffsetDateTime.now(clock);
        drafts.lockOwner(subject.tenantId(), subject.userId());
        var command = drafts.beginCommand(subject.tenantId(), subject.userId(),
                "DRAFT_DISCARD", idempotencyKey, request, now);
        if (command.replay() != null) return new DiscardScheduleDraftResponse(
                command.replay().resultId(), command.replay().resultVersion(), true);
        StoredDraft draft = drafts.find(subject.tenantId(), subject.userId(), true)
                .orElseThrow(MeetingScheduleDraftService::missing);
        expected(draft, request.expectedVersion());
        drafts.delete(draft);
        drafts.removeMutableReceipts(subject.tenantId(), subject.userId());
        long resultVersion = draft.version() + 1;
        drafts.completeCommand(subject.tenantId(), subject.userId(), command, "DISCARDED",
                draft.draftId(), resultVersion, now.plusDays(MAX_RETENTION_DAYS));
        audit.workspaceChanged(subject, "meeting.schedule-draft.discarded",
                "MEETING_SCHEDULE_DRAFT", draft.draftId().toString(),
                VideoMeetingCommandPolicy.correlation(correlationId),
                Map.of("version", resultVersion));
        return new DiscardScheduleDraftResponse(draft.draftId(), resultVersion, true);
    }

    private StoredDraft activeDraft(MeetingRequestContext.Subject subject, boolean lock) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return drafts.find(subject.tenantId(), subject.userId(), lock)
                .filter(candidate -> candidate.retentionUntil().isAfter(now))
                .orElseThrow(MeetingScheduleDraftService::missing);
    }

    private SaveScheduleDraftRequest content(StoredDraft draft) {
        DraftRecurrence recurrence = draft.recurrenceFrequency() == null ? null
                : new DraftRecurrence(draft.recurrenceFrequency(), draft.recurrenceInterval(),
                        draft.recurrenceOccurrenceCount());
        return new SaveScheduleDraftRequest(
                draft.version(), draft.title(), draft.agenda(), draft.startsAt(),
                draft.durationMinutes(), draft.timeZone(), draft.accessScope(),
                draft.waitingRoomEnabled(), draft.allowJoinBeforeHost(),
                drafts.participantIds(draft), drafts.agendaItems(draft), recurrence,
                draft.sourceTemplateId(), draft.sourceTemplateVersion(), draft.lastStep());
    }

    private VideoMeetingDtos.ScheduleMeetingRequest strictMeeting(
            MeetingRequestContext.Subject subject,
            SaveScheduleDraftRequest request) {
        validatePartial(subject, request);
        if (blank(request.title()) || request.startsAt() == null
                || !request.startsAt().toInstant().isAfter(clock.instant())
                || request.durationMinutes() == null
                || blank(request.timeZone()) || blank(request.accessScope())
                || request.waitingRoomEnabled() == null
                || request.allowJoinBeforeHost() == null) throw invalid();
        try {
            ZoneId.of(request.timeZone());
        } catch (DateTimeException exception) {
            throw invalid();
        }
        List<DraftAgendaItem> draftItems = list(request.agendaItems());
        int planned = 0;
        for (DraftAgendaItem item : draftItems) {
            if (blank(item.title()) || item.plannedMinutes() == null) throw invalid();
            planned += item.plannedMinutes();
        }
        if (planned > request.durationMinutes()) throw invalid();
        List<AgendaItemInput> items = draftItems.stream().map(item -> new AgendaItemInput(
                item.itemId(), item.title(), item.objective(),
                item.ownerUserId(), item.plannedMinutes())).toList();
        return new VideoMeetingDtos.ScheduleMeetingRequest(
                request.title(), null, request.agenda(), request.startsAt(),
                request.durationMinutes(), request.timeZone(),
                AccessScope.valueOf(request.accessScope()), request.waitingRoomEnabled(),
                null, request.allowJoinBeforeHost(), false, false,
                list(request.participantUserIds()), null, items,
                request.sourceTemplateId(), request.sourceTemplateVersion());
    }

    private void validatePartial(
            MeetingRequestContext.Subject subject,
            SaveScheduleDraftRequest request) {
        if (request == null || request.expectedVersion() != null && request.expectedVersion() < 0
                || invalidText(request.title(), 240) || invalidText(request.agenda(), 8_000)
                || invalidText(request.timeZone(), 80)
                || request.durationMinutes() != null
                    && (request.durationMinutes() < 5 || request.durationMinutes() > 1_440)
                || request.accessScope() != null
                    && !Set.of("INTERNAL", "INVITED").contains(request.accessScope())
                || Boolean.TRUE.equals(request.allowJoinBeforeHost())
                || request.lastStep() != null
                    && !Set.of("DETAILS", "SCHEDULE", "RECURRENCE", "REVIEW")
                            .contains(request.lastStep())
                || (request.sourceTemplateId() == null)
                    != (request.sourceTemplateVersion() == null)) throw invalid();
        if (request.timeZone() != null && !request.timeZone().isBlank()) {
            try {
                ZoneId.of(request.timeZone());
            } catch (DateTimeException exception) {
                throw invalid();
            }
        }
        DraftRecurrence recurrence = request.recurrence();
        if (recurrence != null && (!Set.of("NONE", "WEEKLY", "MONTHLY")
                .contains(recurrence.frequency()) || recurrence.interval() < 1
                || recurrence.interval() > 12 || recurrence.occurrenceCount() < 2
                || recurrence.occurrenceCount() > 52)) throw invalid();

        List<Long> participantIds = list(request.participantUserIds());
        if (participantIds.size() > 200 || participantIds.contains(subject.userId())
                || new HashSet<>(participantIds).size() != participantIds.size()
                || participantIds.stream().anyMatch(id -> id == null || id <= 0)
                || drafts.activeParticipantIds(subject.tenantId(), participantIds).size()
                        != participantIds.size()) throw invalid();
        Set<Long> owners = new HashSet<>(participantIds);
        owners.add(subject.userId());
        List<DraftAgendaItem> items = list(request.agendaItems());
        if (items.size() > 50) throw invalid();
        Set<UUID> itemIds = new HashSet<>();
        for (DraftAgendaItem item : items) {
            if (item == null || item.itemId() != null && !itemIds.add(item.itemId())
                    || invalidText(item.title(), 240) || invalidText(item.objective(), 2_000)
                    || item.plannedMinutes() != null
                        && (item.plannedMinutes() < 1 || item.plannedMinutes() > 1_440)
                    || item.ownerUserId() != null && !owners.contains(item.ownerUserId())) {
                throw invalid();
            }
        }
        validateSource(subject, request.sourceTemplateId(), request.sourceTemplateVersion());
    }

    private SaveScheduleDraftRequest canonical(SaveScheduleDraftRequest request) {
        if (request == null) throw invalid();
        List<DraftAgendaItem> items = list(request.agendaItems()).stream()
                .map(item -> item == null ? null : new DraftAgendaItem(
                        item.itemId(), trimmed(item.title()), trimmed(item.objective()),
                        item.ownerUserId(), item.plannedMinutes()))
                .toList();
        DraftRecurrence recurrence = request.recurrence() == null ? null
                : new DraftRecurrence(trimmed(request.recurrence().frequency()),
                        request.recurrence().interval(), request.recurrence().occurrenceCount());
        return new SaveScheduleDraftRequest(
                request.expectedVersion(), trimmed(request.title()), trimmed(request.agenda()),
                request.startsAt(), request.durationMinutes(), trimmed(request.timeZone()),
                trimmed(request.accessScope()), request.waitingRoomEnabled(),
                request.allowJoinBeforeHost(), List.copyOf(list(request.participantUserIds())),
                items, recurrence, request.sourceTemplateId(), request.sourceTemplateVersion(),
                request.lastStep() == null ? "DETAILS" : trimmed(request.lastStep()));
    }

    private boolean sourceAccessible(
            MeetingRequestContext.Subject subject,
            StoredDraft draft) {
        return draft.sourceTemplateId() == null || templates.revisionAccessible(
                subject.tenantId(), subject.userId(), draft.sourceTemplateId(),
                draft.sourceTemplateVersion());
    }

    private void validateSource(
            MeetingRequestContext.Subject subject,
            StoredDraft draft) {
        validateSource(subject, draft.sourceTemplateId(), draft.sourceTemplateVersion());
    }

    private void validateSource(
            MeetingRequestContext.Subject subject,
            UUID templateId,
            Long templateVersion) {
        if ((templateId == null) != (templateVersion == null)) throw invalid();
        if (templateId != null && (templateVersion < 0 || !templates.revisionAccessible(
                subject.tenantId(), subject.userId(), templateId, templateVersion))) {
            throw missing();
        }
    }

    private DraftRecurrence requiredRecurring(DraftRecurrence recurrence) {
        if (recurrence == null || "NONE".equals(recurrence.frequency())) throw invalid();
        return recurrence;
    }

    private VideoMeetingDtos.MeetingCreatedResponse created(UUID meetingId) {
        VideoMeetingDtos.MeetingDetailResponse detail = meetings.detail(meetingId);
        return new VideoMeetingDtos.MeetingCreatedResponse(detail, detail.meetingCode());
    }

    private String derivedMeetingKey(
            MeetingRequestContext.Subject subject,
            StoredDraft draft,
            String commandKey) {
        return UUID.nameUUIDFromBytes(("schedule-draft-commit-v1|" + subject.tenantId()
                + "|" + subject.userId() + "|" + draft.draftId() + "|" + commandKey)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private MeetingRequestContext.Subject subject() {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        if ((!subject.permissions().contains("APP.MEETINGS:CREATE")
                && !subject.permissions().contains("APP.MEETINGS:MANAGE"))
                || subject.roles().contains("PROVIDER_SUPPORT")) throw forbidden();
        return subject;
    }

    private void expected(StoredDraft draft, Long version) {
        if (version == null || version != draft.version()) throw conflict();
    }

    private boolean invalidText(String value, int max) {
        return value != null && (value.length() > max
                || value.chars().anyMatch(character -> character == 0
                    || character < 32 && character != '\n' && character != '\t'));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private int size(List<?> value) {
        return value == null ? 0 : value.size();
    }

    private <T> List<T> list(List<T> value) {
        return value == null ? List.of() : value;
    }

    private static BaseException invalid() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                "The private schedule draft input is invalid.");
    }

    private static BaseException conflict() {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT,
                "The schedule draft changed. Reload it before retrying.");
    }

    private static BaseException missing() {
        return new BaseException(ErrorCode.ENTITY_NOT_FOUND,
                "The private schedule draft was not found.");
    }

    private static BaseException forbidden() {
        return new BaseException(ErrorCode.FORBIDDEN,
                "Schedule draft authority is required.");
    }

    private static BaseException unavailable() {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                "Schedule draft retention is not ready.");
    }
}
