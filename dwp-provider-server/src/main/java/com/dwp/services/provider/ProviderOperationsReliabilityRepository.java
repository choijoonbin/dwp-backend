package com.dwp.services.provider;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class ProviderOperationsReliabilityRepository {

    private final JdbcTemplate jdbc;

    ProviderOperationsReliabilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    ProviderDtos.ReliabilityControlOverview reliabilityControl() {
        List<ProviderDtos.ServiceLevelObjectiveSummary> objectives = serviceLevelObjectives();
        List<ProviderDtos.GovernanceDriftSummary> drift = governanceDrift();
        List<ProviderDtos.MaintenanceWindowSummary> maintenance = maintenanceWindows();
        return new ProviderDtos.ReliabilityControlOverview(
                Instant.now(),
                objectives.stream().filter(item -> "HEALTHY".equals(item.complianceState())).count(),
                objectives.stream().filter(item -> "AT_RISK".equals(item.complianceState())).count(),
                objectives.stream().filter(item -> "EXHAUSTED".equals(item.complianceState())).count(),
                drift.size(),
                maintenance.stream().filter(item ->
                        Set.of("DRAFT", "SCHEDULED", "IN_PROGRESS").contains(item.lifecycleState())).count(),
                objectives, drift, maintenance);
    }

    List<ProviderDtos.ServiceLevelObjectiveSummary> serviceLevelObjectives() {
        return jdbc.query("""
                SELECT objective.service_level_objective_id,
                       objective.objective_key,
                       objective.display_name,
                       objective.service_key,
                       service.display_name AS service_name,
                       service.criticality,
                       objective.indicator_type,
                       objective.scope_type,
                       CASE objective.scope_type
                           WHEN 'GLOBAL' THEN 'Global'
                           WHEN 'REGION' THEN objective.region_key
                           WHEN 'CELL' THEN cell.display_name
                           WHEN 'TENANT' THEN tenant.display_name
                       END AS scope_label,
                       objective.target_pct,
                       objective.compliance_window_days,
                       snapshot.achieved_pct,
                       snapshot.error_budget_remaining_pct,
                       snapshot.burn_rate,
                       COALESCE(snapshot.compliance_state, 'NO_DATA') AS compliance_state,
                       snapshot.measurement_source,
                       snapshot.observed_at
                  FROM prv_service_level_objectives objective
                  JOIN prv_service_catalog service ON service.service_key = objective.service_key
                  LEFT JOIN prv_deployment_cells cell
                    ON cell.deployment_cell_id = objective.deployment_cell_id
                  LEFT JOIN prv_tenants tenant
                    ON tenant.provider_tenant_id = objective.provider_tenant_id
                  LEFT JOIN LATERAL (
                        SELECT candidate.*
                          FROM prv_service_level_snapshots candidate
                         WHERE candidate.service_level_objective_id = objective.service_level_objective_id
                         ORDER BY candidate.observed_at DESC, candidate.service_level_snapshot_id DESC
                         LIMIT 1
                  ) snapshot ON TRUE
                 WHERE objective.lifecycle_state = 'ACTIVE'
                 ORDER BY CASE service.criticality
                              WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 ELSE 3 END,
                          service.provisioning_order, objective.objective_key
                """, this::serviceLevelObjective);
    }

    List<ProviderDtos.GovernanceDriftSummary> governanceDrift() {
        return jdbc.query("""
                WITH latest AS (
                    SELECT DISTINCT ON (evaluation.control_key, evaluation.target_type, evaluation.target_id)
                           evaluation.*
                      FROM prv_governance_evaluations evaluation
                     ORDER BY evaluation.control_key, evaluation.target_type,
                              evaluation.target_id, evaluation.evaluated_at DESC,
                              evaluation.governance_evaluation_id DESC
                )
                SELECT latest.governance_evaluation_id,
                       latest.control_key,
                       control.display_name AS control_name,
                       control.control_category,
                       control.control_behavior,
                       control.guidance_level,
                       control.risk_tier,
                       latest.target_type,
                       latest.target_id,
                       latest.provider_tenant_id,
                       tenant.display_name AS tenant_name,
                       latest.evaluation_result,
                       latest.expected_snapshot::text,
                       latest.observed_snapshot::text,
                       control.remediation_operation_type,
                       latest.evaluated_at
                  FROM latest
                  JOIN prv_governance_controls control ON control.control_key = latest.control_key
                  LEFT JOIN prv_tenants tenant
                    ON tenant.provider_tenant_id = latest.provider_tenant_id
                 WHERE latest.evaluation_result IN ('NON_COMPLIANT', 'ERROR')
                   AND control.lifecycle_state = 'ACTIVE'
                 ORDER BY CASE control.risk_tier WHEN 'L3' THEN 1 WHEN 'L2' THEN 2 ELSE 3 END,
                          latest.evaluated_at DESC
                 LIMIT 200
                """, this::governanceDrift);
    }

    List<ProviderDtos.MaintenanceWindowSummary> maintenanceWindows() {
        return jdbc.query("""
                SELECT maintenance.maintenance_window_id,
                       maintenance.operation_id,
                       maintenance.tracking_key,
                       maintenance.title,
                       maintenance.summary,
                       maintenance.scope_type,
                       CASE maintenance.scope_type
                           WHEN 'GLOBAL' THEN 'Global'
                           WHEN 'SERVICE' THEN service.display_name
                           WHEN 'REGION' THEN maintenance.region_key
                           WHEN 'CELL' THEN cell.display_name
                           WHEN 'TENANT' THEN tenant.display_name
                       END AS scope_label,
                       maintenance.impact_type,
                       maintenance.expected_impact_seconds,
                       maintenance.lifecycle_state,
                       maintenance.starts_at,
                       maintenance.ends_at,
                       maintenance.customer_notice_at,
                       maintenance.minimum_notice_hours,
                       maintenance.customer_notice_at IS NOT NULL
                           AND maintenance.customer_notice_at <= maintenance.starts_at
                               - make_interval(hours => maintenance.minimum_notice_hours)
                           AS notice_compliant,
                       maintenance.version
                  FROM prv_maintenance_windows maintenance
                  LEFT JOIN prv_service_catalog service ON service.service_key = maintenance.service_key
                  LEFT JOIN prv_deployment_cells cell
                    ON cell.deployment_cell_id = maintenance.deployment_cell_id
                  LEFT JOIN prv_tenants tenant
                    ON tenant.provider_tenant_id = maintenance.provider_tenant_id
                 WHERE maintenance.ends_at >= CURRENT_TIMESTAMP - INTERVAL '90 days'
                 ORDER BY CASE maintenance.lifecycle_state
                              WHEN 'IN_PROGRESS' THEN 1 WHEN 'SCHEDULED' THEN 2
                              WHEN 'DRAFT' THEN 3 ELSE 4 END,
                          maintenance.starts_at
                 LIMIT 200
                """, this::maintenanceWindow);
    }

    UUID createMaintenanceWindow(
            ProviderDtos.CreateMaintenanceWindowRequest request,
            Long operatorId,
            UUID operationId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_maintenance_windows (
                    maintenance_window_id, tracking_key, title, summary, scope_type,
                    service_key, region_key, deployment_cell_id, provider_tenant_id,
                    impact_type, expected_impact_seconds, lifecycle_state,
                    starts_at, ends_at, customer_notice_at, minimum_notice_hours,
                    operation_id, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?)
                """, id, request.trackingKey().trim(), request.title().trim(), request.summary().trim(),
                request.scopeType(), nullable(request.serviceKey()), nullable(request.regionKey()),
                request.deploymentCellId(), request.tenantId(), request.impactType(),
                request.expectedImpactSeconds(), request.startsAt(), request.endsAt(),
                request.customerNoticeAt(), request.minimumNoticeHours(), operationId, operatorId, operatorId);
        return id;
    }

    Optional<UUID> scheduleMaintenanceWindow(UUID operationId, Long operatorId) {
        return jdbc.query("""
                UPDATE prv_maintenance_windows
                   SET lifecycle_state = 'SCHEDULED',
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?,
                       version = version + 1
                 WHERE operation_id = ?
                   AND lifecycle_state = 'DRAFT'
                   AND starts_at > CURRENT_TIMESTAMP
                RETURNING maintenance_window_id
                """, (result, ignored) -> result.getObject("maintenance_window_id", UUID.class),
                operatorId, operationId).stream().findFirst();
    }

    void cancelMaintenanceWindow(UUID operationId, Long operatorId) {
        jdbc.update("""
                UPDATE prv_maintenance_windows
                   SET lifecycle_state = 'CANCELLED',
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?,
                       version = version + 1
                 WHERE operation_id = ?
                   AND lifecycle_state = 'DRAFT'
                """, operatorId, operationId);
    }

    Optional<UUID> maintenanceWindowId(UUID operationId) {
        return jdbc.query("""
                SELECT maintenance_window_id
                  FROM prv_maintenance_windows
                 WHERE operation_id = ?
                """, (result, ignored) -> result.getObject("maintenance_window_id", UUID.class),
                operationId).stream().findFirst();
    }

    private ProviderDtos.ServiceLevelObjectiveSummary serviceLevelObjective(
            ResultSet result,
            int ignored) throws SQLException {
        return new ProviderDtos.ServiceLevelObjectiveSummary(
                result.getObject("service_level_objective_id", UUID.class),
                result.getString("objective_key"), result.getString("display_name"),
                result.getString("service_key"), result.getString("service_name"),
                result.getString("criticality"), result.getString("indicator_type"),
                result.getString("scope_type"), result.getString("scope_label"),
                result.getDouble("target_pct"), result.getInt("compliance_window_days"),
                nullableDouble(result, "achieved_pct"),
                nullableDouble(result, "error_budget_remaining_pct"),
                nullableDouble(result, "burn_rate"), result.getString("compliance_state"),
                result.getString("measurement_source"), instant(result, "observed_at"));
    }

    private ProviderDtos.GovernanceDriftSummary governanceDrift(
            ResultSet result,
            int ignored) throws SQLException {
        return new ProviderDtos.GovernanceDriftSummary(
                result.getObject("governance_evaluation_id", UUID.class),
                result.getString("control_key"), result.getString("control_name"),
                result.getString("control_category"), result.getString("control_behavior"),
                result.getString("guidance_level"), result.getString("risk_tier"),
                result.getString("target_type"), result.getString("target_id"),
                result.getObject("provider_tenant_id", UUID.class), result.getString("tenant_name"),
                result.getString("evaluation_result"), result.getString("expected_snapshot"),
                result.getString("observed_snapshot"), result.getString("remediation_operation_type"),
                instant(result, "evaluated_at"));
    }

    private ProviderDtos.MaintenanceWindowSummary maintenanceWindow(
            ResultSet result,
            int ignored) throws SQLException {
        return new ProviderDtos.MaintenanceWindowSummary(
                result.getObject("maintenance_window_id", UUID.class),
                result.getObject("operation_id", UUID.class),
                result.getString("tracking_key"), result.getString("title"),
                result.getString("summary"), result.getString("scope_type"),
                result.getString("scope_label"), result.getString("impact_type"),
                result.getInt("expected_impact_seconds"), result.getString("lifecycle_state"),
                instant(result, "starts_at"), instant(result, "ends_at"),
                instant(result, "customer_notice_at"), result.getInt("minimum_notice_hours"),
                result.getBoolean("notice_compliant"), result.getLong("version"));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Double nullableDouble(ResultSet result, String column) throws SQLException {
        double value = result.getDouble(column);
        return result.wasNull() ? null : value;
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
