package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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

import static com.dwp.services.platform.workplace.WorkplaceResourceCommandDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceTypes.*;

@Repository
class WorkplaceResourceCommandRepository {
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    WorkplaceResourceCommandRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    Optional<BookingRow> booking(long tenantId, long actorId, UUID bookingId) {
        return jdbc.query("""
                SELECT booking.booking_id, booking.resource_id, resource.resource_type,
                       booking.booking_status, booking.starts_at, booking.ends_at, booking.version
                  FROM wp_bookings booking
                  JOIN wp_resources resource
                    ON resource.tenant_id=booking.tenant_id
                   AND resource.resource_id=booking.resource_id
                 WHERE booking.tenant_id=? AND booking.user_id=? AND booking.booking_id=?
                   AND resource.lifecycle_state<>'RETIRED'
                """, (rs, row) -> new BookingRow(
                rs.getObject("booking_id", UUID.class),
                rs.getObject("resource_id", UUID.class),
                ResourceType.valueOf(rs.getString("resource_type")),
                BookingStatus.valueOf(rs.getString("booking_status")),
                rs.getObject("starts_at", OffsetDateTime.class),
                rs.getObject("ends_at", OffsetDateTime.class),
                rs.getLong("version")), tenantId, actorId, bookingId).stream().findFirst();
    }

    Optional<ProviderTruthRow> providerTruth(
            long tenantId, ResourceCommandProviderCapability capability) {
        return jdbc.query("""
                SELECT provider_code, configuration_version, observed_configuration_version,
                       reported_state, received_at, last_success_at, error_code, configured
                  FROM wp_navigation_provider_truth
                 WHERE tenant_id=? AND capability=?
                """, (rs, row) -> new ProviderTruthRow(
                rs.getString("provider_code"), rs.getLong("configuration_version"),
                (Long) rs.getObject("observed_configuration_version"),
                rs.getString("reported_state"),
                rs.getObject("received_at", OffsetDateTime.class),
                rs.getObject("last_success_at", OffsetDateTime.class),
                rs.getString("error_code"), rs.getBoolean("configured")),
                tenantId, capability.name()).stream().findFirst();
    }

    void lock(long tenantId, long actorId, String idempotencyKey) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1,
                        "workplace-resource-command:" + tenantId + ":" + actorId + ":"
                                + idempotencyKey), result -> null);
    }

    void insertPreview(PreviewRow row) {
        jdbc.update("""
                INSERT INTO wp_resource_command_previews
                    (preview_id, tenant_id, actor_user_id, booking_id, resource_id,
                     command_type, expected_booking_version, payload, provider_capability,
                     provider_code, provider_configuration_version, eligible, limitations,
                     expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?,
                        CAST(? AS jsonb), ?, ?)
                """, row.previewId(), row.tenantId(), row.actorId(), row.bookingId(),
                row.resourceId(), row.type().name(), row.expectedBookingVersion(),
                json(row.parameters()), row.capability().name(), row.providerCode(),
                row.providerConfigurationVersion(), row.eligible(), json(row.limitations()),
                row.expiresAt(), row.createdAt());
    }

    Optional<PreviewRow> preview(long tenantId, long actorId, UUID previewId) {
        return jdbc.query("""
                SELECT * FROM wp_resource_command_previews
                 WHERE tenant_id=? AND actor_user_id=? AND preview_id=?
                """, (rs, row) -> preview(rs), tenantId, actorId, previewId)
                .stream().findFirst();
    }

    Optional<CommandRow> command(long tenantId, long actorId, String idempotencyKey) {
        return jdbc.query("""
                SELECT command.*, preview.payload::text
                  FROM wp_resource_commands command
                  JOIN wp_resource_command_previews preview
                    ON preview.tenant_id=command.tenant_id
                   AND preview.preview_id=command.preview_id
                 WHERE command.tenant_id=? AND command.actor_user_id=?
                   AND command.idempotency_key=?
                """, (rs, row) -> command(rs), tenantId, actorId, idempotencyKey)
                .stream().findFirst();
    }

    Optional<CommandRow> command(
            long tenantId, long actorId, UUID bookingId, UUID commandId) {
        return jdbc.query("""
                SELECT command.*, preview.payload::text
                  FROM wp_resource_commands command
                  JOIN wp_resource_command_previews preview
                    ON preview.tenant_id=command.tenant_id
                   AND preview.preview_id=command.preview_id
                 WHERE command.tenant_id=? AND command.actor_user_id=?
                   AND command.booking_id=? AND command.command_id=?
                """, (rs, row) -> command(rs), tenantId, actorId, bookingId, commandId)
                .stream().findFirst();
    }

    Optional<ReconciliationRow> reconciliation(
            long tenantId, long actorId, String idempotencyKey) {
        return jdbc.query("""
                SELECT command_id, request_fingerprint, result_snapshot::text
                  FROM wp_resource_command_reconciliations
                 WHERE tenant_id=? AND actor_user_id=? AND idempotency_key=?
                """, (rs, row) -> new ReconciliationRow(
                rs.getObject("command_id", UUID.class),
                rs.getString("request_fingerprint"),
                readReceipt(rs.getString("result_snapshot"))),
                tenantId, actorId, idempotencyKey).stream().findFirst();
    }

    void insertReconciliation(
            long tenantId,
            long actorId,
            UUID commandId,
            String idempotencyKey,
            String fingerprint,
            CommandReceipt result,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_resource_command_reconciliations
                    (reconciliation_id, tenant_id, actor_user_id, command_id,
                     idempotency_key, request_fingerprint, result_snapshot, created_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, UUID.randomUUID(), tenantId, actorId, commandId, idempotencyKey,
                fingerprint, json(result), now);
    }

    CommandRow insertCommand(
            UUID commandId,
            long tenantId,
            long actorId,
            PreviewRow preview,
            String idempotencyKey,
            String fingerprint,
            WorkplaceResourceCommandProvider.ProviderBinding binding,
            String reason,
            String correlationId,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_resource_commands
                    (command_id, tenant_id, actor_user_id, booking_id, resource_id,
                     preview_id, command_type, idempotency_key, request_fingerprint,
                     command_state, provider_code, provider_configuration_version,
                     credential_reference, provider_operation_reference, result_code,
                     reason, correlation_id, version, accepted_at, completed_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RESULT_UNKNOWN', ?, ?, ?,
                        NULL, 'DISPATCHING', ?, ?, 1, ?, NULL, ?)
                """, commandId, tenantId, actorId, preview.bookingId(), preview.resourceId(),
                preview.previewId(), preview.type().name(), idempotencyKey, fingerprint,
                binding.providerCode(), binding.configurationVersion(),
                binding.credentialReference(), reason, correlationId, now, now);
        return command(tenantId, actorId, preview.bookingId(), commandId).orElseThrow();
    }

    CommandRow applyOutcome(
            CommandRow command,
            WorkplaceResourceCommandProvider.ProviderOutcome outcome,
            OffsetDateTime now) {
        OffsetDateTime completed = outcome.state() == ResourceCommandState.RESULT_UNKNOWN
                ? null : now;
        int changed = jdbc.update("""
                UPDATE wp_resource_commands
                   SET command_state=?, provider_operation_reference=?, result_code=?,
                       version=version+1, completed_at=?, updated_at=?
                 WHERE tenant_id=? AND command_id=? AND version=?
                """, outcome.state().name(), outcome.providerOperationReference(),
                outcome.resultCode(), completed, now, command.tenantId(), command.commandId(),
                command.version());
        if (changed != 1) throw new IllegalStateException("Resource command CAS failed.");
        return command(command.tenantId(), command.actorId(), command.bookingId(),
                command.commandId()).orElseThrow();
    }

    void audit(
            CommandRow command,
            long actorId,
            String action,
            String correlationId,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_audit_events
                    (audit_event_id, tenant_id, action, aggregate_type, aggregate_id,
                     actor_user_id, correlation_id, snapshot, occurred_at)
                VALUES (?, ?, ?, 'WP_RESOURCE_COMMAND', ?, ?, ?,
                        jsonb_build_object(
                            'bookingId', ?, 'resourceId', ?, 'commandType', ?,
                            'state', ?, 'resultCode', ?, 'providerCode', ?,
                            'providerConfigurationVersion', ?,
                            'credentialMaterialPersisted', FALSE), ?)
                """, UUID.randomUUID(), command.tenantId(), action, command.commandId(), actorId,
                correlationId, command.bookingId(), command.resourceId(), command.type().name(),
                command.state().name(), command.resultCode(), command.providerCode(),
                command.providerConfigurationVersion(), now);
    }

    private PreviewRow preview(ResultSet rs) throws SQLException {
        return new PreviewRow(rs.getObject("preview_id", UUID.class), rs.getLong("tenant_id"),
                rs.getLong("actor_user_id"), rs.getObject("booking_id", UUID.class),
                rs.getObject("resource_id", UUID.class),
                ResourceCommandType.valueOf(rs.getString("command_type")),
                rs.getLong("expected_booking_version"),
                read(rs.getString("payload"), STRING_MAP),
                ResourceCommandProviderCapability.valueOf(rs.getString("provider_capability")),
                rs.getString("provider_code"),
                (Long) rs.getObject("provider_configuration_version"),
                rs.getBoolean("eligible"), read(rs.getString("limitations"), STRING_LIST),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private CommandRow command(ResultSet rs) throws SQLException {
        return new CommandRow(rs.getObject("command_id", UUID.class), rs.getLong("tenant_id"),
                rs.getLong("actor_user_id"), rs.getObject("booking_id", UUID.class),
                rs.getObject("resource_id", UUID.class), rs.getObject("preview_id", UUID.class),
                ResourceCommandType.valueOf(rs.getString("command_type")),
                read(rs.getString("payload"), STRING_MAP), rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"),
                ResourceCommandState.valueOf(rs.getString("command_state")),
                rs.getString("provider_code"), rs.getLong("provider_configuration_version"),
                rs.getString("credential_reference"),
                rs.getString("provider_operation_reference"), rs.getString("result_code"),
                rs.getString("reason"), rs.getString("correlation_id"), rs.getLong("version"),
                rs.getObject("accepted_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize resource command evidence.", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid resource command evidence.", exception);
        }
    }

    private CommandReceipt readReceipt(String value) {
        try {
            return mapper.readValue(value, CommandReceipt.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid resource-command receipt snapshot.", exception);
        }
    }

    record BookingRow(
            UUID bookingId,
            UUID resourceId,
            ResourceType resourceType,
            BookingStatus status,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            long version) { }

    record ProviderTruthRow(
            String providerCode,
            long configurationVersion,
            Long observedConfigurationVersion,
            String reportedState,
            OffsetDateTime receivedAt,
            OffsetDateTime lastSuccessAt,
            String errorCode,
            boolean configured) { }

    record PreviewRow(
            UUID previewId,
            long tenantId,
            long actorId,
            UUID bookingId,
            UUID resourceId,
            ResourceCommandType type,
            long expectedBookingVersion,
            Map<String, String> parameters,
            ResourceCommandProviderCapability capability,
            String providerCode,
            Long providerConfigurationVersion,
            boolean eligible,
            List<String> limitations,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt) { }

    record CommandRow(
            UUID commandId,
            long tenantId,
            long actorId,
            UUID bookingId,
            UUID resourceId,
            UUID previewId,
            ResourceCommandType type,
            Map<String, String> parameters,
            String idempotencyKey,
            String fingerprint,
            ResourceCommandState state,
            String providerCode,
            long providerConfigurationVersion,
            String credentialReference,
            String providerOperationReference,
            String resultCode,
            String reason,
            String correlationId,
            long version,
            OffsetDateTime acceptedAt,
            OffsetDateTime completedAt,
            OffsetDateTime updatedAt) { }

    record ReconciliationRow(
            UUID commandId,
            String fingerprint,
            CommandReceipt receipt) { }
}
