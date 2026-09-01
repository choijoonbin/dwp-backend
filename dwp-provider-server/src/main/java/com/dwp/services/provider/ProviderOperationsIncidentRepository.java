package com.dwp.services.provider;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

final class ProviderOperationsIncidentRepository {

    private static final DateTimeFormatter INCIDENT_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;

    ProviderOperationsIncidentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<ProviderDtos.ServiceIncidentSummary> incidents(int limit) {
        int safeLimit = Math.min(200, Math.max(1, limit));
        String sql = """
                SELECT incident.service_incident_id,
                       incident.incident_key,
                       incident.title,
                       incident.severity,
                       incident.lifecycle_state,
                       incident.impact_scope,
                       incident.service_key,
                       incident.region_key,
                       incident.deployment_cell_id,
                       incident.provider_tenant_id,
                       tenant.display_name AS tenant_name,
                       incident.customer_impact,
                       incident.public_summary,
                       owner.display_name AS owner_name,
                       incident.detected_at,
                       incident.started_at,
                       incident.resolved_at,
                       incident.version
                  FROM prv_service_incidents incident
                  LEFT JOIN prv_tenants tenant ON tenant.provider_tenant_id = incident.provider_tenant_id
                  LEFT JOIN prv_operators owner ON owner.provider_operator_id = incident.owner_operator_id
                 ORDER BY CASE incident.lifecycle_state
                              WHEN 'INVESTIGATING' THEN 0
                              WHEN 'IDENTIFIED' THEN 1
                              WHEN 'MONITORING' THEN 2
                              WHEN 'RESOLVED' THEN 3
                              ELSE 4
                          END,
                          CASE incident.severity
                              WHEN 'SEV1' THEN 0 WHEN 'SEV2' THEN 1
                              WHEN 'SEV3' THEN 2 ELSE 3
                          END,
                          incident.detected_at DESC
                 LIMIT %d
                """.formatted(safeLimit);
        return jdbc.query(sql, (RowMapper<ProviderDtos.ServiceIncidentSummary>) this::incident);
    }

    Optional<ProviderOperationsRepository.IncidentRecord> incident(UUID incidentId) {
        return jdbc.query("""
                SELECT service_incident_id, lifecycle_state, version
                  FROM prv_service_incidents
                 WHERE service_incident_id = ?
                """, (result, ignored) -> new ProviderOperationsRepository.IncidentRecord(
                        result.getObject("service_incident_id", UUID.class),
                        result.getString("lifecycle_state"),
                        result.getLong("version")), incidentId).stream().findFirst();
    }

    UUID createIncident(
            ProviderDtos.CreateIncidentRequest request,
            Long operatorId,
            String correlationId) {
        Long sequence = jdbc.queryForObject("SELECT nextval('prv_incident_number_seq')", Long.class);
        String incidentKey = "INC-" + INCIDENT_DATE.format(Instant.now()) + "-"
                + String.format(Locale.ROOT, "%05d", sequence == null ? 0 : sequence);
        UUID incidentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_service_incidents (
                    service_incident_id, incident_key, title, severity,
                    impact_scope, service_key, region_key, deployment_cell_id,
                    provider_tenant_id, customer_impact, public_summary,
                    owner_operator_id, correlation_id, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, incidentId, incidentKey, request.title().trim(), request.severity(),
                request.impactScope(), nullable(request.serviceKey()), nullable(request.regionKey()),
                request.deploymentCellId(), request.tenantId(), request.customerImpact().trim(),
                nullable(request.publicSummary()), operatorId, nullable(correlationId), operatorId, operatorId);
        addIncidentImpact(incidentId, request);
        addIncidentUpdate(incidentId, "INVESTIGATING", request.initialUpdate(), "INTERNAL", operatorId);
        return incidentId;
    }

    boolean updateIncident(
            UUID incidentId,
            String state,
            String message,
            String visibility,
            Long operatorId,
            long version) {
        int changed = jdbc.update("""
                UPDATE prv_service_incidents
                   SET lifecycle_state = ?,
                       resolved_at = CASE
                           WHEN ? IN ('RESOLVED', 'CLOSED') THEN COALESCE(resolved_at, CURRENT_TIMESTAMP)
                           ELSE NULL
                       END,
                       closed_at = CASE WHEN ? = 'CLOSED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?,
                       version = version + 1
                 WHERE service_incident_id = ?
                   AND lifecycle_state <> 'CLOSED'
                   AND version = ?
                """, state, state, state, operatorId, incidentId, version);
        if (changed == 1) addIncidentUpdate(incidentId, state, message, visibility, operatorId);
        return changed == 1;
    }

    private ProviderDtos.ServiceIncidentSummary incident(ResultSet result, int ignored) throws SQLException {
        UUID incidentId = result.getObject("service_incident_id", UUID.class);
        return new ProviderDtos.ServiceIncidentSummary(
                incidentId, result.getString("incident_key"), result.getString("title"),
                result.getString("severity"), result.getString("lifecycle_state"),
                result.getString("impact_scope"), result.getString("service_key"),
                result.getString("region_key"), result.getObject("deployment_cell_id", UUID.class),
                result.getObject("provider_tenant_id", UUID.class), result.getString("tenant_name"),
                result.getString("customer_impact"), result.getString("public_summary"),
                result.getString("owner_name"), instant(result, "detected_at"),
                instant(result, "started_at"), instant(result, "resolved_at"),
                result.getLong("version"), incidentUpdates(incidentId));
    }

    private List<ProviderDtos.IncidentUpdateSummary> incidentUpdates(UUID incidentId) {
        return jdbc.query("""
                SELECT incident_update.service_incident_update_id,
                       incident_update.lifecycle_state,
                       incident_update.message,
                       incident_update.visibility,
                       operator.display_name AS operator_name,
                       incident_update.created_at
                  FROM prv_service_incident_updates incident_update
                  LEFT JOIN prv_operators operator
                    ON operator.provider_operator_id = incident_update.created_by
                 WHERE incident_update.service_incident_id = ?
                 ORDER BY incident_update.created_at DESC
                """, (result, ignored) -> new ProviderDtos.IncidentUpdateSummary(
                        result.getObject("service_incident_update_id", UUID.class),
                        result.getString("lifecycle_state"), result.getString("message"),
                        result.getString("visibility"), result.getString("operator_name"),
                        instant(result, "created_at")), incidentId);
    }

    private void addIncidentUpdate(
            UUID incidentId,
            String state,
            String message,
            String visibility,
            Long operatorId) {
        jdbc.update("""
                INSERT INTO prv_service_incident_updates (
                    service_incident_id, lifecycle_state, message, visibility, created_by)
                VALUES (?, ?, ?, ?, ?)
                """, incidentId, state, message.trim(), visibility, operatorId);
    }

    private void addIncidentImpact(UUID incidentId, ProviderDtos.CreateIncidentRequest request) {
        if ("GLOBAL".equals(request.impactScope())) return;
        jdbc.update("""
                INSERT INTO prv_service_incident_impacts (
                    service_incident_id, target_type, service_key, region_key,
                    deployment_cell_id, provider_tenant_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, incidentId, request.impactScope(), nullable(request.serviceKey()),
                nullable(request.regionKey()), request.deploymentCellId(), request.tenantId());
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
