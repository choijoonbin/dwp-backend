package com.dwp.services.platform.workplace.safetyoperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyEmergencyContactDtos.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.CommandState;

@Repository
class SafetyEmergencyContactRepository extends SafetyRepositorySupport {
    SafetyEmergencyContactRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        super(jdbc, mapper);
    }

    List<EmergencyContactRow> contacts(long tenantId, boolean activeOnly) {
        return jdbc.query("""
                SELECT * FROM wp_safety_emergency_contacts
                 WHERE tenant_id=? AND (?=FALSE OR active=TRUE)
                 ORDER BY sort_order, contact_id
                """, this::contactRow, tenantId, activeOnly);
    }

    Optional<EmergencyContactRow> contact(long tenantId, UUID contactId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_emergency_contacts
                 WHERE tenant_id=? AND contact_id=?
                """, this::contactRow, tenantId, contactId).stream().findFirst();
    }

    Optional<EmergencyContactRow> configure(
            long tenantId,
            long actorId,
            UUID contactId,
            EmergencyContactConfigurationRequest request,
            OffsetDateTime now) {
        if (request.expectedVersion() == 0) {
            int inserted = jdbc.update("""
                    INSERT INTO wp_safety_emergency_contacts(
                        contact_id,tenant_id,contact_kind,display_name_ko,display_name_en,
                        action_mode,tel_uri,direct_tel_allowed,connector_kind,active,sort_order,
                        version,updated_by,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,1,?,?,?)
                    ON CONFLICT (contact_id) DO NOTHING
                    """, contactId, tenantId, request.kind().name(), request.displayNameKo().trim(),
                    request.displayNameEn().trim(), request.actionMode().name(), request.telUri(),
                    request.directTelAllowed(), connector(request.actionMode()), request.active(),
                    request.sortOrder(), actorId, now, now);
            if (inserted == 0) return Optional.empty();
        } else {
            int updated = jdbc.update("""
                    UPDATE wp_safety_emergency_contacts
                       SET contact_kind=?,display_name_ko=?,display_name_en=?,action_mode=?,
                           tel_uri=?,direct_tel_allowed=?,connector_kind=?,active=?,sort_order=?,
                           version=version+1,updated_by=?,updated_at=?
                     WHERE tenant_id=? AND contact_id=? AND version=?
                    """, request.kind().name(), request.displayNameKo().trim(),
                    request.displayNameEn().trim(), request.actionMode().name(), request.telUri(),
                    request.directTelAllowed(), connector(request.actionMode()), request.active(),
                    request.sortOrder(), actorId, now, tenantId, contactId,
                    request.expectedVersion());
            if (updated == 0) return Optional.empty();
        }
        return contact(tenantId, contactId);
    }

    void insertPreview(EmergencyHandoffPreviewRow row) {
        jdbc.update("""
                INSERT INTO wp_safety_emergency_handoff_previews(
                    handoff_preview_id,tenant_id,command_id,actor_user_id,incident_id,contact_id,
                    expected_incident_version,expected_contact_version,provider_state,
                    provider_code,provider_configuration_version,provider_evidence_reference,
                    eligible,limitations,expires_at,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)
                """, row.previewId(), row.tenantId(), row.commandId(), row.actorUserId(),
                row.incidentId(), row.contactId(), row.expectedIncidentVersion(),
                row.expectedContactVersion(), row.providerState().name(), row.providerCode(),
                row.providerConfigurationVersion(), row.providerEvidenceReference(), row.eligible(),
                json(row.limitations()), row.expiresAt(), row.createdAt());
    }

    Optional<EmergencyHandoffPreviewRow> preview(long tenantId, UUID previewId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_emergency_handoff_previews
                 WHERE tenant_id=? AND handoff_preview_id=?
                """, this::previewRow, tenantId, previewId).stream().findFirst();
    }

    Optional<EmergencyHandoffPreviewRow> previewByCommand(long tenantId, UUID commandId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_emergency_handoff_previews
                 WHERE tenant_id=? AND command_id=?
                """, this::previewRow, tenantId, commandId).stream().findFirst();
    }

    void insertHandoff(EmergencyHandoffRow row) {
        jdbc.update("""
                INSERT INTO wp_safety_emergency_handoffs(
                    handoff_id,tenant_id,command_id,handoff_preview_id,incident_id,contact_id,
                    handoff_state,provider_code,provider_configuration_version,
                    provider_operation_reference,provider_evidence_reference,result_code,
                    version,created_at,completed_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, row.handoffId(), row.tenantId(), row.commandId(), row.previewId(),
                row.incidentId(), row.contactId(), row.state().name(), row.providerCode(),
                row.providerConfigurationVersion(), row.providerOperationReference(),
                row.providerEvidenceReference(), row.resultCode(), row.version(), row.createdAt(),
                row.completedAt(), row.updatedAt());
    }

    void updateCommandStatusHref(long tenantId, UUID commandId, String statusHref) {
        jdbc.update("""
                UPDATE wp_safety_commands
                   SET status_href=?
                 WHERE tenant_id=? AND command_id=?
                """, statusHref, tenantId, commandId);
    }

    Optional<EmergencyHandoffRow> handoff(long tenantId, UUID incidentId, UUID commandId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_emergency_handoffs
                 WHERE tenant_id=? AND incident_id=? AND command_id=?
                """, this::handoffRow, tenantId, incidentId, commandId).stream().findFirst();
    }

    Optional<EmergencyHandoffRow> handoffByCommand(long tenantId, UUID commandId) {
        return jdbc.query("""
                SELECT * FROM wp_safety_emergency_handoffs
                 WHERE tenant_id=? AND command_id=?
                """, this::handoffRow, tenantId, commandId).stream().findFirst();
    }

    void applyProviderResult(
            long tenantId,
            UUID commandId,
            UUID handoffId,
            SafetyEmergencyHandoffProvider.Result result,
            OffsetDateTime now) {
        OffsetDateTime completedAt = result.state() == CommandState.RESULT_UNKNOWN ? null : now;
        jdbc.update("""
                UPDATE wp_safety_emergency_handoffs
                   SET handoff_state=?,result_code=?,provider_operation_reference=?,
                       provider_evidence_reference=?,completed_at=?,version=version+1,updated_at=?
                 WHERE tenant_id=? AND handoff_id=?
                """, result.state().name(), result.resultCode(),
                result.providerOperationReference(), result.providerEvidenceReference(),
                completedAt, now, tenantId, handoffId);
        jdbc.update("""
                UPDATE wp_safety_commands
                   SET command_state=?,result_code=?,provider_operation_reference=?,
                       completed_at=?,version=version+1,updated_at=?
                 WHERE tenant_id=? AND command_id=?
                """, result.state().name(), result.resultCode(),
                result.providerOperationReference(), completedAt, now, tenantId, commandId);
    }

    void audit(
            long tenantId,
            UUID incidentId,
            long actorId,
            String action,
            String resourceType,
            UUID resourceId,
            String correlationId,
            Map<String, Object> snapshot,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_safety_audit_events(
                    audit_event_id,tenant_id,incident_id,actor_user_id,action,resource_type,
                    resource_id,correlation_id,snapshot,occurred_at)
                VALUES(?,?,?,?,?,?,?,?,?::jsonb,?)
                """, UUID.randomUUID(), tenantId, incidentId, actorId, action,
                resourceType, resourceId, correlationId, json(snapshot), now);
    }

    private EmergencyContactRow contactRow(ResultSet rs, int row) throws SQLException {
        return new EmergencyContactRow(
                rs.getObject("contact_id", UUID.class), rs.getLong("tenant_id"),
                EmergencyContactKind.valueOf(rs.getString("contact_kind")),
                rs.getString("display_name_ko"), rs.getString("display_name_en"),
                EmergencyContactActionMode.valueOf(rs.getString("action_mode")),
                rs.getString("tel_uri"), rs.getBoolean("direct_tel_allowed"),
                rs.getString("connector_kind"), rs.getBoolean("active"),
                rs.getInt("sort_order"), rs.getLong("version"), rs.getLong("updated_by"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private EmergencyHandoffPreviewRow previewRow(ResultSet rs, int row) throws SQLException {
        return new EmergencyHandoffPreviewRow(
                rs.getObject("handoff_preview_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("command_id", UUID.class), rs.getLong("actor_user_id"),
                rs.getObject("incident_id", UUID.class), rs.getObject("contact_id", UUID.class),
                rs.getLong("expected_incident_version"), rs.getLong("expected_contact_version"),
                EmergencyContactProviderState.valueOf(rs.getString("provider_state")),
                rs.getString("provider_code"), nullableLong(rs, "provider_configuration_version"),
                rs.getString("provider_evidence_reference"), rs.getBoolean("eligible"),
                list(rs.getString("limitations"), String.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private EmergencyHandoffRow handoffRow(ResultSet rs, int row) throws SQLException {
        return new EmergencyHandoffRow(
                rs.getObject("handoff_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("command_id", UUID.class),
                rs.getObject("handoff_preview_id", UUID.class),
                rs.getObject("incident_id", UUID.class), rs.getObject("contact_id", UUID.class),
                CommandState.valueOf(rs.getString("handoff_state")),
                rs.getString("provider_code"), rs.getLong("provider_configuration_version"),
                rs.getString("provider_operation_reference"),
                rs.getString("provider_evidence_reference"), rs.getString("result_code"),
                rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static String connector(EmergencyContactActionMode mode) {
        return mode == EmergencyContactActionMode.GOVERNED_HANDOFF ? "EMERGENCY_119" : null;
    }
}
