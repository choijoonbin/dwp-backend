package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.MeetingScheduleDraftDtos.DraftAgendaItem;
import com.dwp.services.meeting.videomeeting.api.MeetingScheduleDraftDtos.DraftAgendaItemResponse;
import com.dwp.services.meeting.videomeeting.api.MeetingScheduleDraftDtos.DraftRecurrence;
import com.dwp.services.meeting.videomeeting.api.MeetingScheduleDraftDtos.SaveScheduleDraftRequest;
import com.dwp.services.meeting.videomeeting.api.MeetingScheduleDraftDtos.ScheduleDraftResponse;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos.MeetingPersonResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MeetingScheduleDraftRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MeetingScheduleDraftRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void lockOwner(long tenantId, long ownerUserId) {
        byte[] value = digest(("meeting-schedule-draft|" + tenantId + "|" + ownerUserId)
                .getBytes(StandardCharsets.UTF_8));
        ByteBuffer lock = ByteBuffer.wrap(value);
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", row -> { },
                lock.getInt(), lock.getInt());
    }

    public Optional<StoredDraft> find(long tenantId, long ownerUserId, boolean lock) {
        return jdbc.query("""
                SELECT draft_id, tenant_id, owner_user_id, title, agenda, starts_at,
                       duration_minutes, time_zone, access_scope, waiting_room_enabled,
                       allow_join_before_host, recurrence_frequency, recurrence_interval,
                       recurrence_occurrence_count, source_template_id,
                       source_template_version, last_step, version, retention_until, updated_at
                  FROM vm_meeting_schedule_drafts
                 WHERE tenant_id = ? AND owner_user_id = ?
                """ + (lock ? " FOR UPDATE" : ""), (row, number) -> new StoredDraft(
                        row.getObject("draft_id", UUID.class), row.getLong("tenant_id"),
                        row.getLong("owner_user_id"), row.getString("title"),
                        row.getString("agenda"), row.getObject("starts_at", OffsetDateTime.class),
                        row.getObject("duration_minutes", Integer.class), row.getString("time_zone"),
                        row.getString("access_scope"),
                        row.getObject("waiting_room_enabled", Boolean.class),
                        row.getObject("allow_join_before_host", Boolean.class),
                        row.getString("recurrence_frequency"),
                        row.getObject("recurrence_interval", Integer.class),
                        row.getObject("recurrence_occurrence_count", Integer.class),
                        row.getObject("source_template_id", UUID.class),
                        row.getObject("source_template_version", Long.class),
                        row.getString("last_step"), row.getLong("version"),
                        row.getObject("retention_until", OffsetDateTime.class),
                        row.getObject("updated_at", OffsetDateTime.class)),
                tenantId, ownerUserId).stream().findFirst();
    }

    public StoredDraft create(
            long tenantId,
            long ownerUserId,
            SaveScheduleDraftRequest request,
            OffsetDateTime retentionUntil) {
        UUID draftId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_schedule_drafts (
                    draft_id, tenant_id, owner_user_id, title, agenda, starts_at,
                    duration_minutes, time_zone, access_scope, waiting_room_enabled,
                    allow_join_before_host, recurrence_frequency, recurrence_interval,
                    recurrence_occurrence_count, source_template_id, source_template_version,
                    last_step, retention_until, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, draftId, tenantId, ownerUserId, request.title(), request.agenda(),
                request.startsAt(), request.durationMinutes(), request.timeZone(),
                request.accessScope(), request.waitingRoomEnabled(), request.allowJoinBeforeHost(),
                recurrenceFrequency(request), recurrenceInterval(request),
                recurrenceCount(request), request.sourceTemplateId(),
                request.sourceTemplateVersion(), step(request), retentionUntil,
                ownerUserId, ownerUserId);
        replaceChildren(tenantId, draftId, ownerUserId, request);
        return find(tenantId, ownerUserId, false).orElseThrow();
    }

    public StoredDraft replaceExpired(
            StoredDraft expired,
            SaveScheduleDraftRequest request,
            OffsetDateTime retentionUntil) {
        return update(expired, request, retentionUntil);
    }

    public StoredDraft update(
            StoredDraft current,
            SaveScheduleDraftRequest request,
            OffsetDateTime retentionUntil) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_schedule_drafts
                   SET title = ?, agenda = ?, starts_at = ?, duration_minutes = ?,
                       time_zone = ?, access_scope = ?, waiting_room_enabled = ?,
                       allow_join_before_host = ?, recurrence_frequency = ?,
                       recurrence_interval = ?, recurrence_occurrence_count = ?,
                       source_template_id = ?, source_template_version = ?, last_step = ?,
                       version = version + 1, retention_until = ?,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND draft_id = ? AND owner_user_id = ? AND version = ?
                """, request.title(), request.agenda(), request.startsAt(),
                request.durationMinutes(), request.timeZone(), request.accessScope(),
                request.waitingRoomEnabled(), request.allowJoinBeforeHost(),
                recurrenceFrequency(request), recurrenceInterval(request),
                recurrenceCount(request), request.sourceTemplateId(),
                request.sourceTemplateVersion(), step(request), retentionUntil,
                current.ownerUserId(), current.tenantId(), current.draftId(),
                current.ownerUserId(), current.version());
        if (updated != 1) throw conflict();
        replaceChildren(current.tenantId(), current.draftId(), current.ownerUserId(), request);
        return find(current.tenantId(), current.ownerUserId(), false).orElseThrow();
    }

    public void delete(StoredDraft current) {
        int deleted = jdbc.update("""
                DELETE FROM vm_meeting_schedule_drafts
                 WHERE tenant_id = ? AND draft_id = ? AND owner_user_id = ? AND version = ?
                """, current.tenantId(), current.draftId(),
                current.ownerUserId(), current.version());
        if (deleted != 1) throw conflict();
    }

    public List<Long> activeParticipantIds(long tenantId, List<Long> userIds) {
        if (userIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(userIds.size(), "?"));
        Object[] arguments = new Object[userIds.size() + 1];
        arguments[0] = tenantId;
        for (int index = 0; index < userIds.size(); index++) arguments[index + 1] = userIds.get(index);
        return jdbc.query("SELECT user_id FROM vm_people_snapshot WHERE tenant_id = ? "
                        + "AND lifecycle_state = 'ACTIVE' AND user_id IN (" + placeholders + ")",
                (row, number) -> row.getLong("user_id"), arguments);
    }

    public ScheduleDraftResponse response(StoredDraft draft) {
        List<MeetingPersonResponse> participants = jdbc.query("""
                SELECT person.user_id, person.person_public_id, person.email_address,
                       person.display_name, person.job_title, person.organization_name
                  FROM vm_meeting_schedule_draft_participants participant
                  JOIN vm_people_snapshot person
                    ON person.tenant_id = participant.tenant_id
                   AND person.user_id = participant.user_id
                   AND person.lifecycle_state = 'ACTIVE'
                 WHERE participant.tenant_id = ? AND participant.draft_id = ?
                 ORDER BY participant.position
                """, (row, number) -> new MeetingPersonResponse(
                        row.getLong("user_id"), row.getObject("person_public_id", UUID.class),
                        row.getString("email_address"), row.getString("display_name"),
                        row.getString("job_title"), row.getString("organization_name")),
                draft.tenantId(), draft.draftId());
        List<DraftAgendaItemResponse> items = jdbc.query("""
                SELECT item_id, position, title, objective,
                       CASE WHEN owner_user_id = ? OR EXISTS (
                           SELECT 1
                             FROM vm_meeting_schedule_draft_participants participant
                             JOIN vm_people_snapshot person
                               ON person.tenant_id = participant.tenant_id
                              AND person.user_id = participant.user_id
                              AND person.lifecycle_state = 'ACTIVE'
                            WHERE participant.tenant_id = item.tenant_id
                              AND participant.draft_id = item.draft_id
                              AND participant.user_id = item.owner_user_id)
                       THEN owner_user_id ELSE NULL END AS visible_owner_user_id,
                       planned_minutes
                  FROM vm_meeting_schedule_draft_agenda_items item
                 WHERE tenant_id = ? AND draft_id = ?
                 ORDER BY position
                """, (row, number) -> new DraftAgendaItemResponse(
                        row.getObject("item_id", UUID.class), row.getInt("position"),
                        row.getString("title"), row.getString("objective"),
                        row.getObject("visible_owner_user_id", Long.class),
                        row.getObject("planned_minutes", Integer.class)),
                draft.ownerUserId(), draft.tenantId(), draft.draftId());
        DraftRecurrence recurrence = draft.recurrenceFrequency() == null ? null
                : new DraftRecurrence(draft.recurrenceFrequency(),
                        draft.recurrenceInterval(), draft.recurrenceOccurrenceCount());
        return new ScheduleDraftResponse(
                draft.draftId(), draft.title(), draft.agenda(), draft.startsAt(),
                draft.durationMinutes(), draft.timeZone(), draft.accessScope(),
                draft.waitingRoomEnabled(), draft.allowJoinBeforeHost(), participants, items,
                recurrence, draft.sourceTemplateId(), draft.sourceTemplateVersion(),
                draft.lastStep(), draft.version(), draft.retentionUntil(), draft.updatedAt());
    }

    public List<Long> participantIds(StoredDraft draft) {
        return jdbc.query("""
                SELECT user_id
                  FROM vm_meeting_schedule_draft_participants
                 WHERE tenant_id = ? AND draft_id = ?
                 ORDER BY position
                """, (row, number) -> row.getLong("user_id"),
                draft.tenantId(), draft.draftId());
    }

    public List<DraftAgendaItem> agendaItems(StoredDraft draft) {
        return jdbc.query("""
                SELECT item_id, title, objective, owner_user_id, planned_minutes
                  FROM vm_meeting_schedule_draft_agenda_items
                 WHERE tenant_id = ? AND draft_id = ?
                 ORDER BY position
                """, (row, number) -> new DraftAgendaItem(
                        row.getObject("item_id", UUID.class), row.getString("title"),
                        row.getString("objective"), row.getObject("owner_user_id", Long.class),
                        row.getObject("planned_minutes", Integer.class)),
                draft.tenantId(), draft.draftId());
    }

    public Attempt beginCommand(
            long tenantId,
            long ownerUserId,
            String operation,
            String suppliedKey,
            Object payload,
            OffsetDateTime now) {
        String key = VideoMeetingCommandPolicy.commandKey(suppliedKey);
        String hash;
        try {
            hash = HexFormat.of().formatHex(digest(mapper.writeValueAsBytes(payload)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Schedule draft command cannot be serialized.", exception);
        }
        jdbc.update("""
                DELETE FROM vm_meeting_schedule_draft_commands
                 WHERE tenant_id = ? AND owner_user_id = ? AND operation = ?
                   AND idempotency_key = ? AND retention_until <= ?
                """, tenantId, ownerUserId, operation, key, now);
        CommandReceipt receipt = jdbc.query("""
                SELECT request_sha256, result_type, result_id, result_version, retention_until
                  FROM vm_meeting_schedule_draft_commands
                 WHERE tenant_id = ? AND owner_user_id = ? AND operation = ?
                   AND idempotency_key = ? AND retention_until > ?
                """, (row, number) -> new CommandReceipt(
                        row.getString("request_sha256"), row.getString("result_type"),
                        row.getObject("result_id", UUID.class), row.getLong("result_version"),
                        row.getObject("retention_until", OffsetDateTime.class)),
                tenantId, ownerUserId, operation, key, now).stream().findFirst().orElse(null);
        if (receipt != null
                && !VideoMeetingCommandPolicy.requestHashesMatch(hash, receipt.requestSha256())) {
            throw conflict();
        }
        return new Attempt(operation, key, hash, receipt);
    }

    public void completeCommand(
            long tenantId,
            long ownerUserId,
            Attempt attempt,
            String resultType,
            UUID resultId,
            long resultVersion,
            OffsetDateTime retentionUntil) {
        jdbc.update("""
                INSERT INTO vm_meeting_schedule_draft_commands (
                    tenant_id, owner_user_id, operation, idempotency_key, request_sha256,
                    result_type, result_id, result_version, retention_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, ownerUserId, attempt.operation(), attempt.idempotencyKey(),
                attempt.requestSha256(), resultType, resultId, resultVersion, retentionUntil);
    }

    public void removeMutableReceipts(long tenantId, long ownerUserId) {
        jdbc.update("""
                DELETE FROM vm_meeting_schedule_draft_commands
                 WHERE tenant_id = ? AND owner_user_id = ?
                   AND operation IN ('DRAFT_SAVE', 'DRAFT_DISCARD')
                """, tenantId, ownerUserId);
    }

    private void replaceChildren(
            long tenantId,
            UUID draftId,
            long ownerUserId,
            SaveScheduleDraftRequest request) {
        jdbc.update("DELETE FROM vm_meeting_schedule_draft_agenda_items "
                + "WHERE tenant_id = ? AND draft_id = ?", tenantId, draftId);
        jdbc.update("DELETE FROM vm_meeting_schedule_draft_participants "
                + "WHERE tenant_id = ? AND draft_id = ?", tenantId, draftId);
        List<Long> participants = request.participantUserIds() == null
                ? List.of() : request.participantUserIds();
        for (int position = 0; position < participants.size(); position++) {
            jdbc.update("""
                    INSERT INTO vm_meeting_schedule_draft_participants (
                        tenant_id, draft_id, position, user_id)
                    VALUES (?, ?, ?, ?)
                    """, tenantId, draftId, position, participants.get(position));
        }
        List<DraftAgendaItem> items = request.agendaItems() == null
                ? List.of() : request.agendaItems();
        for (int position = 0; position < items.size(); position++) {
            DraftAgendaItem item = items.get(position);
            jdbc.update("""
                    INSERT INTO vm_meeting_schedule_draft_agenda_items (
                        tenant_id, draft_id, item_id, position, title, objective,
                        owner_user_id, planned_minutes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, draftId,
                    item.itemId() == null ? UUID.randomUUID() : item.itemId(), position,
                    item.title(), item.objective(), item.ownerUserId(), item.plannedMinutes());
        }
    }

    private String recurrenceFrequency(SaveScheduleDraftRequest request) {
        return request.recurrence() == null ? null : request.recurrence().frequency();
    }

    private Integer recurrenceInterval(SaveScheduleDraftRequest request) {
        return request.recurrence() == null ? null : request.recurrence().interval();
    }

    private Integer recurrenceCount(SaveScheduleDraftRequest request) {
        return request.recurrence() == null ? null : request.recurrence().occurrenceCount();
    }

    private String step(SaveScheduleDraftRequest request) {
        return request.lastStep() == null ? "DETAILS" : request.lastStep();
    }

    private byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private BaseException conflict() {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT,
                "The schedule draft changed. Reload it before retrying.");
    }

    public record StoredDraft(
            UUID draftId,
            long tenantId,
            long ownerUserId,
            String title,
            String agenda,
            OffsetDateTime startsAt,
            Integer durationMinutes,
            String timeZone,
            String accessScope,
            Boolean waitingRoomEnabled,
            Boolean allowJoinBeforeHost,
            String recurrenceFrequency,
            Integer recurrenceInterval,
            Integer recurrenceOccurrenceCount,
            UUID sourceTemplateId,
            Long sourceTemplateVersion,
            String lastStep,
            long version,
            OffsetDateTime retentionUntil,
            OffsetDateTime updatedAt) {
    }

    public record CommandReceipt(
            String requestSha256,
            String resultType,
            UUID resultId,
            long resultVersion,
            OffsetDateTime retentionUntil) {
    }

    public record Attempt(
            String operation,
            String idempotencyKey,
            String requestSha256,
            CommandReceipt replay) {
    }
}
