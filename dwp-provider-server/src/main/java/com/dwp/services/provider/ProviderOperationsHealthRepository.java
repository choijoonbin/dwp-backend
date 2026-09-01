package com.dwp.services.provider;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class ProviderOperationsHealthRepository {

    private final JdbcTemplate jdbc;
    private final ProviderOperationsIncidentRepository incidentRepository;

    ProviderOperationsHealthRepository(
            JdbcTemplate jdbc,
            ProviderOperationsIncidentRepository incidentRepository) {
        this.jdbc = jdbc;
        this.incidentRepository = incidentRepository;
    }

    ProviderDtos.CommandCenter commandCenter(ProviderDtos.EstateOverview estate) {
        List<ProviderDtos.ServicePosture> services = servicePostures();
        List<ProviderDtos.CellPosture> cells = cellPostures();
        long incidents = count("""
                SELECT COUNT(*) FROM prv_service_incidents
                 WHERE lifecycle_state NOT IN ('RESOLVED', 'CLOSED')
                """);
        long expiring = count("""
                SELECT COUNT(*) FROM prv_organization_subscriptions
                 WHERE lifecycle_state IN ('TRIAL', 'ACTIVE')
                   AND ends_at BETWEEN CURRENT_TIMESTAMP AND CURRENT_TIMESTAMP + INTERVAL '90 days'
                """);
        List<ProviderDtos.ActionItem> actions = actionItems();
        boolean critical = services.stream().anyMatch(item -> item.failedInstances() > 0)
                || count("""
                        SELECT COUNT(*) FROM prv_service_incidents
                         WHERE severity = 'SEV1' AND lifecycle_state NOT IN ('RESOLVED', 'CLOSED')
                        """) > 0;
        boolean attention = !actions.isEmpty()
                || services.stream().anyMatch(item -> item.degradedInstances() > 0);
        String state = critical ? "CRITICAL" : attention ? "ATTENTION" : "HEALTHY";
        return new ProviderDtos.CommandCenter(
                Instant.now(), state, estate, incidents, expiring, actions, services, cells, recentActivity());
    }

    ProviderDtos.ServiceHealthOverview serviceHealth() {
        List<ProviderDtos.ServicePosture> services = servicePostures();
        List<ProviderDtos.CellPosture> cells = cellPostures();
        List<ProviderDtos.ServiceIncidentSummary> incidents = incidentRepository.incidents(100);
        long total = services.stream().mapToLong(ProviderDtos.ServicePosture::totalInstances).sum();
        long healthy = services.stream().mapToLong(ProviderDtos.ServicePosture::healthyInstances).sum();
        long pending = services.stream().mapToLong(ProviderDtos.ServicePosture::pendingInstances).sum();
        long degraded = services.stream().mapToLong(ProviderDtos.ServicePosture::degradedInstances).sum();
        long failed = services.stream().mapToLong(ProviderDtos.ServicePosture::failedInstances).sum();
        long impacted = services.stream().mapToLong(ProviderDtos.ServicePosture::impactedTenants).sum();
        boolean critical = failed > 0 || incidents.stream().anyMatch(item ->
                "SEV1".equals(item.severity()) && !isResolved(item.lifecycleState()));
        boolean attention = degraded > 0 || pending > 0 || incidents.stream().anyMatch(item ->
                !isResolved(item.lifecycleState()));
        return new ProviderDtos.ServiceHealthOverview(
                Instant.now(), critical ? "CRITICAL" : attention ? "ATTENTION" : "HEALTHY",
                total, healthy, pending, degraded, failed, impacted, services, cells, incidents);
    }

    List<ProviderDtos.ServicePosture> servicePostures() {
        return jdbc.query("""
                SELECT service.service_key,
                       service.display_name,
                       service.criticality,
                       COUNT(instance.tenant_service_instance_id) AS total_instances,
                       COUNT(*) FILTER (WHERE instance.lifecycle_state = 'READY') AS healthy_instances,
                       COUNT(*) FILTER (WHERE instance.lifecycle_state = 'PROVISIONING') AS pending_instances,
                       COUNT(*) FILTER (WHERE instance.lifecycle_state = 'DEGRADED') AS degraded_instances,
                       COUNT(*) FILTER (WHERE instance.lifecycle_state = 'FAILED') AS failed_instances,
                       COUNT(DISTINCT instance.provider_tenant_id) FILTER (
                           WHERE instance.lifecycle_state IN ('DEGRADED', 'FAILED')) AS impacted_tenants,
                       MAX(instance.last_reconciled_at) AS last_reconciled_at
                  FROM prv_service_catalog service
                  LEFT JOIN prv_tenant_service_instances instance
                    ON instance.service_key = service.service_key
                   AND instance.lifecycle_state <> 'RETIRED'
                 WHERE service.lifecycle_state = 'ACTIVE'
                 GROUP BY service.service_key, service.display_name,
                          service.criticality, service.provisioning_order
                 ORDER BY service.provisioning_order
                """, this::servicePosture);
    }

    List<ProviderDtos.CellPosture> cellPostures() {
        return jdbc.query("""
                SELECT cell.deployment_cell_id,
                       cell.cell_key,
                       cell.display_name,
                       cell.region_key,
                       cell.lifecycle_state,
                       cell.placement_capacity,
                       COUNT(DISTINCT instance.provider_tenant_id) AS tenant_count,
                       COUNT(instance.tenant_service_instance_id) AS service_instances,
                       COUNT(*) FILTER (WHERE instance.lifecycle_state = 'READY') AS healthy_instances,
                       ROUND(
                           COUNT(DISTINCT instance.provider_tenant_id)::numeric * 100
                           / cell.placement_capacity, 2) AS saturation_pct,
                       CASE
                           WHEN COUNT(*) FILTER (WHERE instance.lifecycle_state = 'FAILED') > 0 THEN 'CRITICAL'
                           WHEN COUNT(*) FILTER (WHERE instance.lifecycle_state = 'DEGRADED') > 0
                                OR COUNT(DISTINCT instance.provider_tenant_id)::numeric * 100
                                   / cell.placement_capacity >= cell.warning_threshold_pct THEN 'ATTENTION'
                           ELSE 'HEALTHY'
                       END AS health_state
                  FROM prv_deployment_cells cell
                  LEFT JOIN prv_tenant_service_instances instance
                    ON instance.deployment_cell_id = cell.deployment_cell_id
                   AND instance.lifecycle_state <> 'RETIRED'
                 WHERE cell.lifecycle_state <> 'RETIRED'
                 GROUP BY cell.deployment_cell_id
                 ORDER BY cell.region_key, cell.cell_key
                """, this::cellPosture);
    }

    private List<ProviderDtos.ActionItem> actionItems() {
        List<ProviderDtos.ActionItem> items = new ArrayList<>();
        items.addAll(jdbc.query("""
                SELECT 'operation:' || operation.operation_id AS item_id,
                       'CHANGE' AS category,
                       CASE WHEN operation.lifecycle_state = 'FAILED' THEN 'CRITICAL' ELSE 'HIGH' END AS severity,
                       operation.operation_type AS title,
                       COALESCE(operation.failure_message, operation.justification) AS detail,
                       operation.provider_tenant_id,
                       operation.operation_id::text AS target_id,
                       operation.created_at,
                       '/provider/operations' AS route
                  FROM prv_operations operation
                 WHERE operation.lifecycle_state IN ('PREVIEWED', 'PARTIAL', 'FAILED')
                 ORDER BY operation.created_at DESC
                 LIMIT 12
                """, this::actionItem));
        items.addAll(jdbc.query("""
                SELECT 'service:' || instance.tenant_service_instance_id AS item_id,
                       'SERVICE_HEALTH' AS category,
                       CASE WHEN instance.lifecycle_state = 'FAILED' THEN 'CRITICAL' ELSE 'HIGH' END AS severity,
                       service.display_name AS title,
                       tenant.display_name || ' / ' || instance.lifecycle_state AS detail,
                       tenant.provider_tenant_id,
                       instance.tenant_service_instance_id::text AS target_id,
                       instance.updated_at AS created_at,
                       '/provider/health' AS route
                  FROM prv_tenant_service_instances instance
                  JOIN prv_service_catalog service ON service.service_key = instance.service_key
                  JOIN prv_tenants tenant ON tenant.provider_tenant_id = instance.provider_tenant_id
                 WHERE instance.lifecycle_state IN ('DEGRADED', 'FAILED')
                 ORDER BY instance.updated_at DESC
                 LIMIT 12
                """, this::actionItem));
        items.addAll(jdbc.query("""
                SELECT 'domain:' || domain.tenant_domain_id AS item_id,
                       'IDENTITY' AS category,
                       CASE WHEN domain.verification_state = 'FAILED' THEN 'HIGH' ELSE 'MEDIUM' END AS severity,
                       domain.domain_name AS title,
                       domain.verification_state AS detail,
                       tenant.provider_tenant_id,
                       domain.tenant_domain_id::text AS target_id,
                       domain.created_at,
                       '/provider/tenants/' || tenant.provider_tenant_id AS route
                  FROM prv_tenant_domains domain
                  JOIN prv_tenants tenant ON tenant.provider_tenant_id = domain.provider_tenant_id
                 WHERE domain.verification_state = 'FAILED'
                    OR (domain.verification_state = 'PENDING'
                        AND domain.created_at < CURRENT_TIMESTAMP - INTERVAL '24 hours')
                 ORDER BY domain.created_at
                 LIMIT 12
                """, this::actionItem));
        items.addAll(jdbc.query("""
                SELECT 'subscription:' || subscription.organization_subscription_id AS item_id,
                       'COMMERCIAL' AS category,
                       CASE WHEN subscription.ends_at < CURRENT_TIMESTAMP + INTERVAL '30 days'
                            THEN 'HIGH' ELSE 'MEDIUM' END AS severity,
                       organization.display_name AS title,
                       plan.display_name || ' renewal' AS detail,
                       NULL::uuid AS provider_tenant_id,
                       subscription.organization_subscription_id::text AS target_id,
                       subscription.ends_at AS created_at,
                       '/provider/commercial' AS route
                  FROM prv_organization_subscriptions subscription
                  JOIN prv_organizations organization ON organization.organization_id = subscription.organization_id
                  JOIN prv_service_plans plan ON plan.service_plan_id = subscription.service_plan_id
                 WHERE subscription.lifecycle_state IN ('TRIAL', 'ACTIVE')
                   AND subscription.ends_at BETWEEN CURRENT_TIMESTAMP AND CURRENT_TIMESTAMP + INTERVAL '90 days'
                 ORDER BY subscription.ends_at
                 LIMIT 12
                """, this::actionItem));
        items.addAll(jdbc.query("""
                SELECT 'slo:' || objective.service_level_objective_id AS item_id,
                       'RELIABILITY' AS category,
                       CASE snapshot.compliance_state
                           WHEN 'EXHAUSTED' THEN 'CRITICAL' ELSE 'HIGH' END AS severity,
                       objective.display_name AS title,
                       'Error budget ' || COALESCE(
                           ROUND(snapshot.error_budget_remaining_pct, 1)::text || '%', 'unavailable') AS detail,
                       objective.provider_tenant_id,
                       objective.service_level_objective_id::text AS target_id,
                       snapshot.observed_at AS created_at,
                       '/provider/health' AS route
                  FROM prv_service_level_objectives objective
                  JOIN LATERAL (
                        SELECT candidate.*
                          FROM prv_service_level_snapshots candidate
                         WHERE candidate.service_level_objective_id = objective.service_level_objective_id
                         ORDER BY candidate.observed_at DESC, candidate.service_level_snapshot_id DESC
                         LIMIT 1
                  ) snapshot ON TRUE
                 WHERE objective.lifecycle_state = 'ACTIVE'
                   AND snapshot.compliance_state IN ('AT_RISK', 'EXHAUSTED')
                 ORDER BY snapshot.observed_at DESC
                 LIMIT 12
                """, this::actionItem));
        items.addAll(jdbc.query("""
                WITH latest AS (
                    SELECT DISTINCT ON (evaluation.control_key, evaluation.target_type, evaluation.target_id)
                           evaluation.*
                      FROM prv_governance_evaluations evaluation
                     ORDER BY evaluation.control_key, evaluation.target_type,
                              evaluation.target_id, evaluation.evaluated_at DESC,
                              evaluation.governance_evaluation_id DESC
                )
                SELECT 'drift:' || latest.governance_evaluation_id AS item_id,
                       'GOVERNANCE_DRIFT' AS category,
                       CASE control.risk_tier WHEN 'L3' THEN 'CRITICAL'
                            WHEN 'L2' THEN 'HIGH' ELSE 'MEDIUM' END AS severity,
                       control.display_name AS title,
                       COALESCE(tenant.display_name || ' / ', '') || latest.target_type AS detail,
                       latest.provider_tenant_id,
                       latest.governance_evaluation_id::text AS target_id,
                       latest.evaluated_at AS created_at,
                       '/provider/health' AS route
                  FROM latest
                  JOIN prv_governance_controls control ON control.control_key = latest.control_key
                  LEFT JOIN prv_tenants tenant ON tenant.provider_tenant_id = latest.provider_tenant_id
                 WHERE latest.evaluation_result IN ('NON_COMPLIANT', 'ERROR')
                   AND control.lifecycle_state = 'ACTIVE'
                 ORDER BY latest.evaluated_at DESC
                 LIMIT 12
                """, this::actionItem));
        items.addAll(jdbc.query("""
                SELECT 'maintenance:' || maintenance.maintenance_window_id AS item_id,
                       'MAINTENANCE' AS category,
                       CASE
                           WHEN maintenance.customer_notice_at > maintenance.starts_at
                               - make_interval(hours => maintenance.minimum_notice_hours)
                               THEN 'HIGH'
                           ELSE 'MEDIUM'
                       END AS severity,
                       maintenance.title,
                       maintenance.tracking_key || ' / ' || maintenance.impact_type AS detail,
                       maintenance.provider_tenant_id,
                       maintenance.maintenance_window_id::text AS target_id,
                       maintenance.starts_at AS created_at,
                       '/provider/health' AS route
                  FROM prv_maintenance_windows maintenance
                 WHERE maintenance.lifecycle_state IN ('DRAFT', 'SCHEDULED', 'IN_PROGRESS')
                   AND maintenance.starts_at <= CURRENT_TIMESTAMP + INTERVAL '14 days'
                 ORDER BY maintenance.starts_at
                 LIMIT 12
                """, this::actionItem));
        return items.stream()
                .sorted(Comparator
                        .comparingInt((ProviderDtos.ActionItem item) -> severityOrder(item.severity()))
                        .thenComparing(ProviderDtos.ActionItem::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(12)
                .toList();
    }

    private List<ProviderDtos.RecentActivity> recentActivity() {
        return jdbc.query("""
                SELECT audit.audit_event_id,
                       audit.action,
                       audit.event_category,
                       audit.outcome,
                       operator.display_name AS operator_name,
                       tenant.tenant_key,
                       audit.target_type,
                       audit.target_id,
                       audit.occurred_at
                  FROM prv_audit_events audit
                  LEFT JOIN prv_operators operator ON operator.provider_operator_id = audit.provider_operator_id
                  LEFT JOIN prv_tenants tenant ON tenant.provider_tenant_id = audit.provider_tenant_id
                 ORDER BY audit.occurred_at DESC
                 LIMIT 10
                """, (result, ignored) -> new ProviderDtos.RecentActivity(
                        result.getObject("audit_event_id", UUID.class),
                        result.getString("action"),
                        result.getString("event_category"),
                        result.getString("outcome"),
                        result.getString("operator_name"),
                        result.getString("tenant_key"),
                        result.getString("target_type"),
                        result.getString("target_id"),
                        instant(result, "occurred_at")));
    }

    private ProviderDtos.ServicePosture servicePosture(ResultSet result, int ignored) throws SQLException {
        return new ProviderDtos.ServicePosture(
                result.getString("service_key"), result.getString("display_name"),
                result.getString("criticality"), result.getLong("total_instances"),
                result.getLong("healthy_instances"), result.getLong("pending_instances"),
                result.getLong("degraded_instances"), result.getLong("failed_instances"),
                result.getLong("impacted_tenants"), instant(result, "last_reconciled_at"));
    }

    private ProviderDtos.CellPosture cellPosture(ResultSet result, int ignored) throws SQLException {
        return new ProviderDtos.CellPosture(
                result.getObject("deployment_cell_id", UUID.class), result.getString("cell_key"),
                result.getString("display_name"), result.getString("region_key"),
                result.getString("lifecycle_state"), result.getInt("placement_capacity"),
                result.getLong("tenant_count"), result.getLong("service_instances"),
                result.getLong("healthy_instances"), result.getDouble("saturation_pct"),
                result.getString("health_state"));
    }

    private ProviderDtos.ActionItem actionItem(ResultSet result, int ignored) throws SQLException {
        return new ProviderDtos.ActionItem(
                result.getString("item_id"), result.getString("category"),
                result.getString("severity"), result.getString("title"),
                result.getString("detail"), result.getObject("provider_tenant_id", UUID.class),
                result.getString("target_id"), instant(result, "created_at"), result.getString("route"));
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private boolean isResolved(String state) {
        return "RESOLVED".equals(state) || "CLOSED".equals(state);
    }

    private int severityOrder(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            default -> 3;
        };
    }
}
