package com.dwp.services.people.organization;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


class OrganizationScenarioValidationRepository extends OrganizationScenarioProjectionRepository {
    OrganizationScenarioValidationRepository(JdbcTemplate jdbc) { super(jdbc); }

    public UUID recordValidation(
            Long tenantId,
            OrganizationScenarioRepository.ScenarioRecord scenario,
            String triggerType,
            OrganizationScenarioDtos.DecisionPack decision,
            String baselineMetrics,
            String proposedMetrics,
            String metricDeltas,
            String checks,
            Long actorId,
            String correlationId) {
        UUID validationRunId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ppl_organization_scenario_validation_runs (
                    organization_scenario_validation_run_id, tenant_id,
                    organization_scenario_id, scenario_version, trigger_type,
                    decision_state, readiness_score, baseline_fingerprint,
                    observed_fingerprint, baseline_current, blocking_issue_count,
                    warning_count, baseline_metrics, proposed_metrics, metric_deltas,
                    decision_checks, evaluated_by, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb),
                        CAST(? AS jsonb), ?, ?)
                """, validationRunId, tenantId, scenario.scenarioId(), scenario.version(), triggerType,
                decision.decisionState(), decision.readinessScore(), decision.baselineFingerprint(),
                decision.observedFingerprint(), decision.baselineCurrent(), decision.blockingIssueCount(),
                decision.warningCount(), baselineMetrics, proposedMetrics, metricDeltas, checks,
                actorId, correlationId);
        return validationRunId;
    }

    public List<OrganizationScenarioDtos.ValidationRunSummary> validationHistory(
            Long tenantId,
            UUID scenarioId) {
        return jdbc.query("""
                SELECT organization_scenario_validation_run_id, scenario_version,
                       trigger_type, decision_state, readiness_score, baseline_current,
                       blocking_issue_count, warning_count, evaluated_at,
                       evaluated_by, correlation_id
                  FROM ppl_organization_scenario_validation_runs
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                 ORDER BY evaluated_at DESC, organization_scenario_validation_run_id DESC
                 LIMIT 50
                """, (result, ignored) -> new OrganizationScenarioDtos.ValidationRunSummary(
                result.getObject("organization_scenario_validation_run_id", UUID.class),
                result.getLong("scenario_version"), result.getString("trigger_type"),
                result.getString("decision_state"), result.getInt("readiness_score"),
                result.getBoolean("baseline_current"), result.getInt("blocking_issue_count"),
                result.getInt("warning_count"), result.getTimestamp("evaluated_at").toInstant(),
                result.getLong("evaluated_by"), result.getString("correlation_id")),
                tenantId, scenarioId);
    }

}
