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


abstract class OrganizationScenarioJdbcRepository {
    protected final JdbcTemplate jdbc;

    OrganizationScenarioJdbcRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<OrganizationScenarioDtos.Change> changes(Long tenantId, UUID scenarioId) {
        return jdbc.query("""
                SELECT organization_scenario_change_id, change_sequence, change_type,
                       payload_schema_version,
                       target_kind, target_reference, related_reference, effective_date,
                       before_snapshot::text, after_snapshot::text,
                       estimated_headcount_delta, estimated_fte_delta,
                       estimated_cost_delta, cost_currency,
                       validation_state, validation_message, version
                  FROM ppl_organization_scenario_changes
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                 ORDER BY change_sequence
                """, (result, ignored) -> new OrganizationScenarioDtos.Change(
                result.getObject("organization_scenario_change_id", UUID.class),
                result.getInt("change_sequence"), result.getString("change_type"),
                result.getInt("payload_schema_version"),
                result.getString("target_kind"), result.getString("target_reference"),
                result.getString("related_reference"), date(result, "effective_date"),
                result.getString("before_snapshot"), result.getString("after_snapshot"),
                result.getInt("estimated_headcount_delta"), result.getDouble("estimated_fte_delta"),
                result.getBigDecimal("estimated_cost_delta"), result.getString("cost_currency"),
                result.getString("validation_state"), result.getString("validation_message"),
                result.getLong("version")), tenantId, scenarioId);
    }


    protected OrganizationScenarioDtos.Scenario scenario(Long tenantId, ResultSet result)
            throws SQLException {
        UUID scenarioId = result.getObject("organization_scenario_id", UUID.class);
        return new OrganizationScenarioDtos.Scenario(
                scenarioId, result.getString("scenario_key"), result.getString("name"),
                result.getString("description"),
                result.getObject("source_scenario_id", UUID.class),
                date(result, "baseline_date"),
                date(result, "effective_date"), result.getString("lifecycle_state"),
                result.getLong("owner_user_id"), instant(result, "submitted_at"),
                instant(result, "published_at"),
                result.getObject("publication_validation_run_id", UUID.class),
                result.getString("publication_evidence_state"), result.getLong("version"),
                changes(tenantId, scenarioId), approvalSummary(tenantId, scenarioId));
    }


    protected OrganizationScenarioDtos.Approval approvalSummary(Long tenantId, UUID scenarioId) {
        return jdbc.query("""
                SELECT organization_scenario_approval_id, gate_key, required_role_code,
                       separation_of_duties, lifecycle_state, requested_by, decided_by,
                       request_reason, decision_reason, requested_at, decided_at,
                       expires_at, request_validation_run_id,
                       decision_validation_run_id, evidence_binding_state, version
                  FROM ppl_organization_scenario_approvals
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                 ORDER BY gate_order DESC LIMIT 1
                """, (result, ignored) -> new OrganizationScenarioDtos.Approval(
                result.getObject("organization_scenario_approval_id", UUID.class),
                result.getString("gate_key"), result.getString("required_role_code"),
                result.getBoolean("separation_of_duties"), effectiveApprovalState(result),
                result.getLong("requested_by"), nullableLong(result, "decided_by"),
                result.getString("request_reason"), result.getString("decision_reason"),
                instant(result, "requested_at"), instant(result, "decided_at"),
                instant(result, "expires_at"),
                result.getObject("request_validation_run_id", UUID.class),
                result.getObject("decision_validation_run_id", UUID.class),
                result.getString("evidence_binding_state"), result.getLong("version")),
                tenantId, scenarioId).stream().findFirst().orElse(null);
    }

    protected LocalDate date(ResultSet result, String column) throws SQLException {
        Date value = result.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    protected Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    protected Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    protected String effectiveApprovalState(ResultSet result) throws SQLException {
        String state = result.getString("lifecycle_state");
        Instant expiresAt = instant(result, "expires_at");
        return "PENDING".equals(state) && expiresAt != null && !expiresAt.isAfter(Instant.now())
                ? "EXPIRED"
                : state;
    }

    protected String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    protected boolean advanceDraft(
            Long tenantId,
            UUID scenarioId,
            Long actorId,
            long expectedVersion) {
        return jdbc.update("""
                UPDATE ppl_organization_scenarios
                   SET version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                   AND lifecycle_state = 'DRAFT' AND version = ?
                """, actorId, tenantId, scenarioId, expectedVersion) == 1;
    }

    protected int nextSequence(UUID scenarioId) {
        Integer sequence = jdbc.queryForObject("""
                SELECT COALESCE(MAX(change_sequence), 0) + 1
                  FROM ppl_organization_scenario_changes
                 WHERE organization_scenario_id = ?
                """, Integer.class, scenarioId);
        return sequence == null ? 1 : sequence;
    }


    protected record RelationshipSlice(long relationshipId, LocalDate effectiveStartDate) {
    }
}
