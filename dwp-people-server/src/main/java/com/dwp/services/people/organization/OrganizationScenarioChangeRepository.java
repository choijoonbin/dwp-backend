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


class OrganizationScenarioChangeRepository extends OrganizationScenarioLifecycleRepository {
    OrganizationScenarioChangeRepository(JdbcTemplate jdbc) { super(jdbc); }

    public List<OrganizationScenarioRepository.MoveRecord> moves(Long tenantId, UUID scenarioId) {
        return jdbc.query("""
                SELECT organization_scenario_change_id, target_reference, related_reference
                  FROM ppl_organization_scenario_changes
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                   AND change_type = 'MOVE_ORGANIZATION'
                 ORDER BY change_sequence
                """, (result, ignored) -> new OrganizationScenarioRepository.MoveRecord(
                result.getObject("organization_scenario_change_id", UUID.class),
                UUID.fromString(result.getString("target_reference")),
                UUID.fromString(result.getString("related_reference"))), tenantId, scenarioId);
    }

    public List<OrganizationScenarioRepository.PositionMoveRecord> positionMoves(Long tenantId, UUID scenarioId) {
        return jdbc.query("""
                SELECT organization_scenario_change_id, target_reference, related_reference
                  FROM ppl_organization_scenario_changes
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                   AND change_type = 'MOVE_POSITION'
                 ORDER BY change_sequence
                """, (result, ignored) -> new OrganizationScenarioRepository.PositionMoveRecord(
                result.getObject("organization_scenario_change_id", UUID.class),
                UUID.fromString(result.getString("target_reference")),
                UUID.fromString(result.getString("related_reference"))), tenantId, scenarioId);
    }

    public List<OrganizationScenarioRepository.PositionCreateRecord> positionCreates(Long tenantId, UUID scenarioId) {
        return jdbc.query("""
                SELECT organization_scenario_change_id,
                       target_reference,
                       after_snapshot ->> 'positionKey' AS position_key,
                       after_snapshot ->> 'title' AS title,
                       after_snapshot ->> 'organizationId' AS organization_id,
                       after_snapshot ->> 'reportsToPositionId' AS parent_position_id,
                       after_snapshot ->> 'positionType' AS position_type,
                       after_snapshot ->> 'criticality' AS criticality,
                       (after_snapshot ->> 'budgetedFte')::numeric AS budgeted_fte,
                       (after_snapshot ->> 'annualCostAmount')::numeric AS annual_cost_amount,
                       after_snapshot ->> 'costCurrency' AS cost_currency,
                       (after_snapshot ->> 'availabilityDate')::date AS availability_date
                  FROM ppl_organization_scenario_changes
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                   AND change_type = 'CREATE_POSITION'
                 ORDER BY change_sequence
                """, (result, ignored) -> new OrganizationScenarioRepository.PositionCreateRecord(
                result.getObject("organization_scenario_change_id", UUID.class),
                UUID.fromString(result.getString("target_reference")),
                result.getString("position_key"), result.getString("title"),
                UUID.fromString(result.getString("organization_id")),
                UUID.fromString(result.getString("parent_position_id")),
                result.getString("position_type"), result.getString("criticality"),
                result.getBigDecimal("budgeted_fte"), result.getBigDecimal("annual_cost_amount"),
                result.getString("cost_currency"), date(result, "availability_date")),
                tenantId, scenarioId);
    }

    public List<OrganizationScenarioRepository.PositionCloseRecord> positionCloses(Long tenantId, UUID scenarioId) {
        return jdbc.query("""
                SELECT organization_scenario_change_id, target_reference
                  FROM ppl_organization_scenario_changes
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                   AND change_type = 'CLOSE_POSITION'
                 ORDER BY change_sequence
                """, (result, ignored) -> new OrganizationScenarioRepository.PositionCloseRecord(
                result.getObject("organization_scenario_change_id", UUID.class),
                UUID.fromString(result.getString("target_reference"))), tenantId, scenarioId);
    }

    public boolean positionKeyExists(Long tenantId, UUID scenarioId, String positionKey) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM ppl_positions
                     WHERE tenant_id = ? AND UPPER(position_key) = UPPER(?)
                    UNION ALL
                    SELECT 1 FROM ppl_organization_scenario_changes
                     WHERE tenant_id = ? AND organization_scenario_id = ?
                       AND change_type = 'CREATE_POSITION'
                       AND UPPER(after_snapshot ->> 'positionKey') = UPPER(?)
                )
                """, Boolean.class, tenantId, positionKey, tenantId, scenarioId, positionKey);
        return Boolean.TRUE.equals(exists);
    }

    public boolean removeChange(
            Long tenantId,
            UUID scenarioId,
            UUID changeId,
            Long actorId,
            long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE ppl_organization_scenarios
                   SET version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                   AND lifecycle_state = 'DRAFT' AND version = ?
                """, actorId, tenantId, scenarioId, expectedVersion);
        if (updated != 1) return false;
        int deleted = jdbc.update("""
                DELETE FROM ppl_organization_scenario_changes
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                   AND organization_scenario_change_id = ?
                """, tenantId, scenarioId, changeId);
        if (deleted != 1) {
            throw new IllegalStateException("The scenario change no longer exists.");
        }
        return true;
    }

}
