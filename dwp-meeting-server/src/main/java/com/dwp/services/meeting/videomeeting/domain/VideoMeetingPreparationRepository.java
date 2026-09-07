package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.api.VideoMeetingPreparationDtos.AgendaItemInput;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingPreparationDtos.AgendaItemResponse;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingPreparationDtos.InvitationResponse;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingPreparationDtos.MaterialResponse;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingPreparationDtos.MyPreparationResponse;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingPreparationDtos.RegisterMaterialRequest;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class VideoMeetingPreparationRepository {
    private final JdbcTemplate jdbc;

    public VideoMeetingPreparationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Versions(long agendaVersion, long materialsVersion, long invitationRevision) { }

    Versions versions(Meeting meeting) {
        return jdbc.query("""
                SELECT agenda_version, materials_version, invitation_revision
                  FROM vm_meeting_preparations
                 WHERE tenant_id = ? AND meeting_id = ?
                """, (rs, row) -> new Versions(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
                meeting.tenantId(), meeting.meetingId()).stream().findFirst()
                .orElseThrow(VideoMeetingPreparationPolicy::conflict);
    }

    List<AgendaItemResponse> agenda(Meeting meeting) {
        return jdbc.query("""
                SELECT item.item_id, item.position, item.title, item.objective,
                       participant.user_id AS visible_owner_user_id,
                       participant.display_name AS visible_owner_name, item.planned_minutes
                  FROM vm_meeting_agenda_items item
                  LEFT JOIN vm_meeting_participants participant
                    ON participant.tenant_id = item.tenant_id
                   AND participant.meeting_id = item.meeting_id
                   AND participant.user_id = item.owner_user_id
                   AND participant.attendance_state <> 'DENIED'
                 WHERE item.tenant_id = ? AND item.meeting_id = ?
                 ORDER BY item.position
                """, (rs, row) -> new AgendaItemResponse(
                        rs.getObject("item_id", UUID.class), rs.getInt("position"),
                        rs.getString("title"), rs.getString("objective"),
                        rs.getObject("visible_owner_user_id", Long.class),
                        rs.getString("visible_owner_name"),
                        rs.getObject("planned_minutes", Integer.class)),
                meeting.tenantId(), meeting.meetingId());
    }

    boolean activeInternalParticipant(Meeting meeting, long userId) {
        Boolean active = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM vm_meeting_participants participant
                      JOIN vm_people_snapshot person
                        ON person.tenant_id = participant.tenant_id
                       AND person.user_id = participant.user_id
                       AND person.lifecycle_state = 'ACTIVE'
                     WHERE participant.tenant_id = ? AND participant.meeting_id = ?
                       AND participant.user_id = ?
                       AND participant.attendance_state <> 'DENIED')
                """, Boolean.class, meeting.tenantId(), meeting.meetingId(), userId);
        return Boolean.TRUE.equals(active);
    }

    MyPreparationResponse myPreparation(
            Meeting meeting, UUID participantId, long currentAgendaVersion) {
        PersonalPreparation header = jdbc.query("""
                SELECT agenda_version, version, updated_at
                  FROM vm_meeting_personal_preparations
                 WHERE tenant_id = ? AND meeting_id = ? AND participant_id = ?
                """, (row, number) -> new PersonalPreparation(
                        row.getLong("agenda_version"), row.getLong("version"),
                        row.getObject("updated_at", OffsetDateTime.class)),
                meeting.tenantId(), meeting.meetingId(), participantId)
                .stream().findFirst().orElse(null);
        if (header == null) return new MyPreparationResponse(
                currentAgendaVersion, 0, List.of(), null);
        List<UUID> prepared = header.agendaVersion() == currentAgendaVersion
                ? jdbc.query("""
                        SELECT agenda_item_id
                          FROM vm_meeting_personal_preparation_items
                         WHERE tenant_id = ? AND meeting_id = ? AND participant_id = ?
                         ORDER BY agenda_item_id
                        """, (row, number) -> row.getObject("agenda_item_id", UUID.class),
                        meeting.tenantId(), meeting.meetingId(), participantId)
                : List.of();
        return new MyPreparationResponse(currentAgendaVersion, header.version(),
                prepared, header.updatedAt());
    }

    long replaceMyPreparation(
            Meeting meeting,
            UUID participantId,
            long expectedAgendaVersion,
            long expectedVersion,
            List<UUID> preparedAgendaItemIds) {
        Versions versions = versions(meeting);
        if (versions.agendaVersion() != expectedAgendaVersion) {
            throw VideoMeetingPreparationPolicy.conflict();
        }
        if (!preparedAgendaItemIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(
                    preparedAgendaItemIds.size(), "?"));
            Object[] arguments = new Object[preparedAgendaItemIds.size() + 2];
            arguments[0] = meeting.tenantId();
            arguments[1] = meeting.meetingId();
            for (int index = 0; index < preparedAgendaItemIds.size(); index++) {
                arguments[index + 2] = preparedAgendaItemIds.get(index);
            }
            Integer present = jdbc.queryForObject("""
                    SELECT COUNT(*)::INTEGER
                      FROM vm_meeting_agenda_items
                     WHERE tenant_id = ? AND meeting_id = ? AND item_id IN (
                    """ + placeholders + ")", Integer.class, arguments);
            if (present == null || present != preparedAgendaItemIds.size()) {
                throw VideoMeetingPreparationPolicy.invalid();
            }
        }
        PersonalPreparation current = jdbc.query("""
                SELECT agenda_version, version, updated_at
                  FROM vm_meeting_personal_preparations
                 WHERE tenant_id = ? AND meeting_id = ? AND participant_id = ?
                 FOR UPDATE
                """, (row, number) -> new PersonalPreparation(
                        row.getLong("agenda_version"), row.getLong("version"),
                        row.getObject("updated_at", OffsetDateTime.class)),
                meeting.tenantId(), meeting.meetingId(), participantId)
                .stream().findFirst().orElse(null);
        long nextVersion;
        if (current == null) {
            if (expectedVersion != 0) throw VideoMeetingPreparationPolicy.conflict();
            jdbc.update("""
                    INSERT INTO vm_meeting_personal_preparations (
                        tenant_id, meeting_id, participant_id, agenda_version, version)
                    VALUES (?, ?, ?, ?, 1)
                    """, meeting.tenantId(), meeting.meetingId(), participantId,
                    expectedAgendaVersion);
            nextVersion = 1;
        } else {
            if (current.version() != expectedVersion) {
                throw VideoMeetingPreparationPolicy.conflict();
            }
            int updated = jdbc.update("""
                    UPDATE vm_meeting_personal_preparations
                       SET agenda_version = ?, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = ? AND meeting_id = ? AND participant_id = ?
                       AND version = ?
                    """, expectedAgendaVersion, meeting.tenantId(), meeting.meetingId(),
                    participantId, expectedVersion);
            if (updated != 1) throw VideoMeetingPreparationPolicy.conflict();
            nextVersion = expectedVersion + 1;
        }
        jdbc.update("""
                DELETE FROM vm_meeting_personal_preparation_items
                 WHERE tenant_id = ? AND meeting_id = ? AND participant_id = ?
                """, meeting.tenantId(), meeting.meetingId(), participantId);
        for (UUID itemId : preparedAgendaItemIds) {
            jdbc.update("""
                    INSERT INTO vm_meeting_personal_preparation_items (
                        tenant_id, meeting_id, participant_id, agenda_item_id)
                    VALUES (?, ?, ?, ?)
                    """, meeting.tenantId(), meeting.meetingId(), participantId, itemId);
        }
        return nextVersion;
    }

    List<InvitationResponse> responses(Meeting meeting, long userId) {
        return jdbc.query("""
                SELECT response.*, participant.display_name, participant.user_id
                  FROM vm_meeting_invitation_responses response
                  JOIN vm_meeting_participants participant
                    ON participant.tenant_id = response.tenant_id
                   AND participant.meeting_id = response.meeting_id
                   AND participant.participant_id = response.participant_id
                 WHERE response.tenant_id = ? AND response.meeting_id = ?
                   AND participant.attendance_state <> 'DENIED'
                 ORDER BY participant.display_name, response.participant_id
                """, (rs, row) -> new InvitationResponse(
                        rs.getObject("participant_id", UUID.class), rs.getString("display_name"),
                        rs.getString("response_state"), rs.getLong("invitation_revision"),
                        rs.getObject("responded_at", OffsetDateTime.class), rs.getLong("version"),
                        Long.valueOf(userId).equals(rs.getObject("user_id", Long.class))),
                meeting.tenantId(), meeting.meetingId());
    }

    List<MaterialResponse> materials(Meeting meeting, boolean includeOpaqueReference) {
        return jdbc.query("""
                SELECT material_id, display_name, content_type, reference_provider,
                       CASE WHEN ? THEN opaque_reference ELSE NULL END AS visible_reference,
                       source_version, classification, size_bytes, content_sha256,
                       retention_until, access_verification_state, version
                 FROM vm_meeting_preparation_materials
                 WHERE tenant_id = ? AND meeting_id = ? AND lifecycle_state = 'ACTIVE'
                   AND retention_until > CURRENT_TIMESTAMP
                 ORDER BY created_at, material_id
                """, (rs, row) -> new MaterialResponse(
                        rs.getObject("material_id", UUID.class), rs.getString("display_name"),
                        rs.getString("content_type"), rs.getString("reference_provider"),
                        rs.getString("visible_reference"), rs.getString("source_version"),
                        rs.getString("classification"), rs.getObject("size_bytes", Long.class),
                        rs.getString("content_sha256"),
                        rs.getObject("retention_until", OffsetDateTime.class),
                        rs.getString("access_verification_state"), rs.getLong("version")),
                includeOpaqueReference, meeting.tenantId(), meeting.meetingId());
    }

    long registerMaterial(Meeting meeting, RegisterMaterialRequest request,
            int retentionDays, long actorId) {
        if (jdbc.update("""
                UPDATE vm_meeting_preparations
                   SET materials_version = materials_version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND meeting_id = ? AND materials_version = ?
                """, meeting.tenantId(), meeting.meetingId(),
                request.expectedMaterialsVersion()) != 1)
            throw VideoMeetingPreparationPolicy.conflict();
        try {
            jdbc.update("""
                    INSERT INTO vm_meeting_preparation_materials (
                        material_id, tenant_id, meeting_id, display_name, content_type,
                        reference_provider, opaque_reference, source_version, classification,
                        size_bytes, content_sha256, retention_until, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            CURRENT_TIMESTAMP + make_interval(days => ?), ?, ?)
                    """, UUID.randomUUID(), meeting.tenantId(), meeting.meetingId(),
                    request.displayName().trim(), request.contentType().trim().toLowerCase(java.util.Locale.ROOT),
                    request.referenceProvider(), request.opaqueReference(), request.sourceVersion(),
                    request.classification(), request.sizeBytes(), request.contentSha256(),
                    retentionDays, actorId, actorId);
        } catch (DataIntegrityViolationException exception) {
            throw VideoMeetingPreparationPolicy.conflict();
        }
        return request.expectedMaterialsVersion() + 1;
    }

    long removeMaterial(Meeting meeting, UUID materialId,
            long expectedMaterialsVersion, long expectedVersion, long actorId) {
        if (jdbc.update("""
                UPDATE vm_meeting_preparations
                   SET materials_version = materials_version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND meeting_id = ? AND materials_version = ?
                """, meeting.tenantId(), meeting.meetingId(), expectedMaterialsVersion) != 1)
            throw VideoMeetingPreparationPolicy.conflict();
        if (jdbc.update("""
                UPDATE vm_meeting_preparation_materials
                   SET lifecycle_state = 'REMOVED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND material_id = ?
                   AND lifecycle_state = 'ACTIVE' AND version = ?
                """, actorId, meeting.tenantId(), meeting.meetingId(), materialId,
                expectedVersion) != 1) throw VideoMeetingPreparationPolicy.conflict();
        return expectedMaterialsVersion + 1;
    }

    void createAgenda(Meeting meeting, List<AgendaItemInput> items, long actorId) {
        if (!items.isEmpty()) replaceAgenda(meeting, 0, items, actorId);
    }

    long replaceAgenda(Meeting meeting, long expectedVersion, List<AgendaItemInput> items, long actorId) {
        for (AgendaItemInput item : items) {
            if (item.ownerUserId() != null) {
                Integer present = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM vm_meeting_participants participant
                        JOIN vm_people_snapshot person
                          ON person.tenant_id = participant.tenant_id AND person.user_id = participant.user_id
                         WHERE participant.tenant_id = ? AND participant.meeting_id = ?
                           AND participant.user_id = ? AND participant.attendance_state <> 'DENIED'
                           AND person.lifecycle_state = 'ACTIVE'
                        """, Integer.class, meeting.tenantId(), meeting.meetingId(), item.ownerUserId());
                if (present == null || present != 1) throw VideoMeetingPreparationPolicy.invalid();
            }
        }
        if (jdbc.update("""
                UPDATE vm_meeting_preparations
                   SET agenda_version = agenda_version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND meeting_id = ? AND agenda_version = ?
                """, meeting.tenantId(), meeting.meetingId(), expectedVersion) != 1)
            throw VideoMeetingPreparationPolicy.conflict();
        jdbc.update("DELETE FROM vm_meeting_agenda_items WHERE tenant_id = ? AND meeting_id = ?",
                meeting.tenantId(), meeting.meetingId());
        for (int position = 0; position < items.size(); position++) {
            AgendaItemInput item = items.get(position);
            jdbc.update("""
                    INSERT INTO vm_meeting_agenda_items (
                        item_id, tenant_id, meeting_id, position, title, objective,
                        owner_user_id, planned_minutes, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, item.itemId() == null ? UUID.randomUUID() : item.itemId(),
                    meeting.tenantId(), meeting.meetingId(), position, item.title(), item.objective(),
                    item.ownerUserId(), item.plannedMinutes(), actorId, actorId);
        }
        return expectedVersion + 1;
    }

    long respond(Meeting meeting, UUID participantId, long invitationRevision,
            long expectedVersion, String response) {
        if (jdbc.update("""
                UPDATE vm_meeting_invitation_responses
                   SET response_state = ?, responded_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND meeting_id = ? AND participant_id = ?
                   AND invitation_revision = ? AND version = ?
                """, response, meeting.tenantId(), meeting.meetingId(), participantId,
                invitationRevision, expectedVersion) != 1) throw VideoMeetingPreparationPolicy.conflict();
        return expectedVersion + 1;
    }

    Optional<Long> replay(Meeting meeting, long actorId, String operation, String key, String digest) {
        return jdbc.query("""
                SELECT request_sha256, result_version FROM vm_meeting_preparation_commands
                 WHERE tenant_id = ? AND meeting_id = ? AND actor_user_id = ?
                   AND operation = ? AND idempotency_key = ?
                """, (rs, row) -> {
                    if (!VideoMeetingCommandPolicy.requestHashesMatch(rs.getString(1), digest))
                        throw VideoMeetingPreparationPolicy.conflict();
                    return rs.getLong(2);
                }, meeting.tenantId(), meeting.meetingId(), actorId, operation, key)
                .stream().findFirst();
    }

    void complete(Meeting meeting, long actorId, String operation, String key, String digest, long version) {
        jdbc.update("""
                INSERT INTO vm_meeting_preparation_commands (
                    command_id, tenant_id, meeting_id, actor_user_id, operation,
                    idempotency_key, request_sha256, result_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), meeting.tenantId(), meeting.meetingId(), actorId,
                operation, key, digest, version);
    }

    private record PersonalPreparation(
            long agendaVersion, long version, OffsetDateTime updatedAt) { }
}
