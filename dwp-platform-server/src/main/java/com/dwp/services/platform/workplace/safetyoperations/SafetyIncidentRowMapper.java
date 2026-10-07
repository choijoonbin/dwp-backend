package com.dwp.services.platform.workplace.safetyoperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;

final class SafetyIncidentRowMapper extends SafetyRepositorySupport {
    SafetyIncidentRowMapper(JdbcTemplate jdbc, ObjectMapper mapper) {
        super(jdbc, mapper);
    }

    <T> T preview(ResultSet rs, int row, PreviewFactory<T> factory) throws SQLException {
        return factory.create(rs.getObject("activation_preview_id", UUID.class),
                rs.getString("incident_type"), Severity.valueOf(rs.getString("severity")),
                rs.getObject("site_id", UUID.class), list(rs.getString("floor_ids"), UUID.class),
                list(rs.getString("zone_ids"), UUID.class), rs.getString("message"),
                rs.getString("safety_action"), rs.getString("assembly_point"),
                list(rs.getString("channels"), DeliveryChannel.class),
                list(rs.getString("excluded_subject_keys"), String.class),
                rs.getObject("audience_snapshot_id", UUID.class), rs.getBoolean("eligible"),
                list(rs.getString("limitations"), String.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    <T> T incident(ResultSet rs, int row, IncidentFactory<T> factory) throws SQLException {
        return factory.create(rs.getObject("incident_id", UUID.class),
                rs.getString("incident_number"), rs.getString("incident_type"),
                Severity.valueOf(rs.getString("severity")),
                IncidentState.valueOf(rs.getString("incident_state")),
                rs.getObject("site_id", UUID.class), list(rs.getString("floor_ids"), UUID.class),
                list(rs.getString("zone_ids"), UUID.class), rs.getString("message"),
                rs.getString("safety_action"), rs.getString("assembly_point"),
                list(rs.getString("channels"), DeliveryChannel.class),
                rs.getObject("audience_snapshot_id", UUID.class), rs.getLong("version"),
                rs.getObject("activated_at", OffsetDateTime.class),
                rs.getObject("closed_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    <T> T command(ResultSet rs, int row, CommandFactory<T> factory) throws SQLException {
        return factory.create(rs.getObject("command_id", UUID.class),
                rs.getObject("incident_id", UUID.class), rs.getString("command_type"),
                rs.getString("request_fingerprint"),
                CommandState.valueOf(rs.getString("command_state")), rs.getString("reason"),
                rs.getString("correlation_id"), rs.getString("status_href"),
                rs.getString("result_code"), rs.getString("provider_operation_reference"),
                rs.getLong("version"), rs.getObject("accepted_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    <T> T scope(ResultSet rs, int row, ScopeFactory<T> factory) throws SQLException {
        return factory.create(rs.getObject("scope_revision_id", UUID.class),
                rs.getObject("incident_id", UUID.class), rs.getLong("incident_version"),
                list(rs.getString("previous_floor_ids"), UUID.class),
                list(rs.getString("previous_zone_ids"), UUID.class),
                list(rs.getString("proposed_floor_ids"), UUID.class),
                list(rs.getString("proposed_zone_ids"), UUID.class),
                rs.getString("proposed_message"),
                rs.getObject("audience_snapshot_id", UUID.class),
                rs.getString("revision_state"), rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    <T> T connector(ResultSet rs, int row, ConnectorFactory<T> factory) throws SQLException {
        return factory.create(rs.getObject("connector_id", UUID.class),
                ConnectorKind.valueOf(rs.getString("connector_kind")),
                rs.getString("provider_code"), rs.getBoolean("configured"),
                rs.getLong("configuration_version"),
                nullableLong(rs, "observed_configuration_version"),
                rs.getString("reported_state"), rs.getString("evidence_reference"),
                rs.getObject("source_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class),
                rs.getObject("last_success_at", OffsetDateTime.class),
                rs.getString("error_code"), rs.getLong("version"));
    }

    static SafetyDispatchProvider.ProviderContext providerContext(ResultSet rs)
            throws SQLException {
        String provider = rs.getString("provider_code");
        if (provider == null) return null;
        return new SafetyDispatchProvider.ProviderContext(
                DeliveryChannel.valueOf(rs.getString("channel")), provider,
                rs.getLong("provider_configuration_version"),
                rs.getString("provider_credential_reference"));
    }

    static boolean terminal(AttemptState state) {
        return state == AttemptState.DELIVERED || state == AttemptState.DELIVERY_FAILED
                || state == AttemptState.RESULT_UNKNOWN;
    }

    @FunctionalInterface
    interface PreviewFactory<T> {
        T create(UUID id, String incidentType, Severity severity, UUID siteId,
                 java.util.List<UUID> floorIds, java.util.List<UUID> zoneIds, String message,
                 String safetyAction, String assemblyPoint,
                 java.util.List<DeliveryChannel> channels, java.util.List<String> excludedKeys,
                 UUID snapshotId, boolean eligible, java.util.List<String> limitations,
                 OffsetDateTime expiresAt, OffsetDateTime createdAt);
    }

    @FunctionalInterface
    interface IncidentFactory<T> {
        T create(UUID id, String number, String type, Severity severity, IncidentState state,
                 UUID siteId, java.util.List<UUID> floorIds, java.util.List<UUID> zoneIds,
                 String message, String safetyAction, String assemblyPoint,
                 java.util.List<DeliveryChannel> channels, UUID snapshotId, long version,
                 OffsetDateTime activatedAt, OffsetDateTime closedAt, OffsetDateTime updatedAt);
    }

    @FunctionalInterface
    interface CommandFactory<T> {
        T create(UUID id, UUID incidentId, String type, String fingerprint,
                 CommandState state, String reason, String correlationId, String statusHref,
                 String resultCode, String providerReference, long version,
                 OffsetDateTime acceptedAt, OffsetDateTime completedAt, OffsetDateTime updatedAt);
    }

    @FunctionalInterface
    interface ScopeFactory<T> {
        T create(UUID id, UUID incidentId, long incidentVersion,
                 java.util.List<UUID> previousFloors, java.util.List<UUID> previousZones,
                 java.util.List<UUID> proposedFloors, java.util.List<UUID> proposedZones,
                 String message, UUID snapshotId, String state,
                 OffsetDateTime expiresAt, OffsetDateTime createdAt);
    }

    @FunctionalInterface
    interface ConnectorFactory<T> {
        T create(UUID id, ConnectorKind kind, String provider, boolean configured,
                 long configurationVersion, Long observedVersion, String reportedState,
                 String evidenceReference, OffsetDateTime sourceAt, OffsetDateTime receivedAt,
                 OffsetDateTime lastSuccessAt, String errorCode, long version);
    }
}
