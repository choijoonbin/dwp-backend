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


class OrganizationScenarioProjectionRepository extends OrganizationScenarioChangeRepository {
    OrganizationScenarioProjectionRepository(JdbcTemplate jdbc) { super(jdbc); }

    public OrganizationScenarioRepository.OrganizationRecord organization(Long tenantId, UUID publicId, LocalDate asOf) {
        return jdbc.query("""
                SELECT organization.organization_id, organization.public_id,
                       organization.name, parent.public_id AS parent_public_id,
                       parent.name AS parent_name
                  FROM ppl_organizations organization
                  LEFT JOIN LATERAL (
                        SELECT relationship.parent_organization_id
                          FROM ppl_organization_relationships relationship
                         WHERE relationship.tenant_id = organization.tenant_id
                           AND relationship.child_organization_id = organization.organization_id
                           AND relationship.relationship_type = 'SUPERVISORY'
                           AND relationship.primary_relationship = TRUE
                           AND relationship.effective_start_date <= ?
                           AND (relationship.effective_end_date IS NULL
                                OR relationship.effective_end_date >= ?)
                         ORDER BY relationship.effective_start_date DESC,
                                  relationship.organization_relationship_id DESC
                         LIMIT 1
                  ) current_parent ON TRUE
                  LEFT JOIN ppl_organizations parent
                    ON parent.tenant_id = organization.tenant_id
                   AND parent.organization_id = current_parent.parent_organization_id
                 WHERE organization.tenant_id = ? AND organization.public_id = ?
                   AND organization.lifecycle_state = 'ACTIVE'
                """, (result, ignored) -> new OrganizationScenarioRepository.OrganizationRecord(
                result.getLong("organization_id"), result.getObject("public_id", UUID.class),
                result.getString("name"), result.getObject("parent_public_id", UUID.class),
                result.getString("parent_name")), Date.valueOf(asOf), Date.valueOf(asOf),
                tenantId, publicId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Organization not found."));
    }

    public OrganizationScenarioRepository.PositionRecord position(Long tenantId, UUID publicId, LocalDate asOf) {
        return jdbc.query("""
                SELECT position.position_id, position.public_id, position.title,
                       parent.public_id AS parent_public_id, parent.title AS parent_title
                  FROM ppl_positions position
                  LEFT JOIN LATERAL (
                       SELECT relationship.parent_position_id
                         FROM ppl_position_relationships relationship
                        WHERE relationship.tenant_id = position.tenant_id
                          AND relationship.child_position_id = position.position_id
                          AND relationship.relationship_type = 'SUPERVISORY'
                          AND relationship.primary_relationship = TRUE
                          AND relationship.effective_start_date <= ?
                          AND (relationship.effective_end_date IS NULL
                               OR relationship.effective_end_date >= ?)
                        ORDER BY CASE relationship.relationship_source
                                     WHEN 'SCENARIO' THEN 0 WHEN 'HRIS' THEN 1
                                     WHEN 'POSITION' THEN 2 ELSE 3 END,
                                 relationship.effective_start_date DESC,
                                 relationship.position_relationship_id DESC
                        LIMIT 1
                  ) current_parent ON TRUE
                  LEFT JOIN ppl_positions parent
                    ON parent.tenant_id = position.tenant_id
                   AND parent.position_id = current_parent.parent_position_id
                 WHERE position.tenant_id = ? AND position.public_id = ?
                   AND position.valid_from <= ?
                   AND (position.valid_to IS NULL OR position.valid_to >= ?)
                   AND position.position_status <> 'CLOSED'
                """, (result, ignored) -> new OrganizationScenarioRepository.PositionRecord(
                result.getLong("position_id"), result.getObject("public_id", UUID.class),
                result.getString("title"), result.getObject("parent_public_id", UUID.class),
                result.getString("parent_title")), Date.valueOf(asOf), Date.valueOf(asOf),
                tenantId, publicId, Date.valueOf(asOf), Date.valueOf(asOf)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Position not found."));
    }

    public OrganizationScenarioRepository.PositionPlanningRecord positionPlanning(Long tenantId, UUID publicId, LocalDate asOf) {
        return jdbc.query("""
                SELECT position.position_id, position.public_id, position.position_key,
                       position.title, organization.public_id AS organization_public_id,
                       organization.name AS organization_name,
                       parent.public_id AS parent_public_id, parent.title AS parent_title,
                       position.position_status, position.position_type, position.criticality,
                       position.budgeted_fte, position.annual_cost_amount,
                       position.cost_currency, position.availability_date, position.valid_from,
                       (SELECT COUNT(*)
                          FROM ppl_assignments assignment
                         WHERE assignment.tenant_id = position.tenant_id
                           AND assignment.position_id = position.position_id
                           AND assignment.effective_start_date <= ?
                           AND (assignment.effective_end_date IS NULL
                                OR assignment.effective_end_date >= ?)) AS incumbent_count,
                       (SELECT COUNT(*)
                          FROM ppl_position_relationships child_relationship
                         WHERE child_relationship.tenant_id = position.tenant_id
                           AND child_relationship.parent_position_id = position.position_id
                           AND child_relationship.relationship_type = 'SUPERVISORY'
                           AND child_relationship.primary_relationship = TRUE
                           AND child_relationship.effective_start_date <= ?
                           AND (child_relationship.effective_end_date IS NULL
                                OR child_relationship.effective_end_date >= ?)) AS subordinate_count
                  FROM ppl_positions position
                  JOIN ppl_organizations organization
                    ON organization.tenant_id = position.tenant_id
                   AND organization.organization_id = position.organization_id
                  LEFT JOIN LATERAL (
                       SELECT relationship.parent_position_id
                         FROM ppl_position_relationships relationship
                        WHERE relationship.tenant_id = position.tenant_id
                          AND relationship.child_position_id = position.position_id
                          AND relationship.relationship_type = 'SUPERVISORY'
                          AND relationship.primary_relationship = TRUE
                          AND relationship.effective_start_date <= ?
                          AND (relationship.effective_end_date IS NULL
                               OR relationship.effective_end_date >= ?)
                        ORDER BY CASE relationship.relationship_source
                                     WHEN 'SCENARIO' THEN 0 WHEN 'HRIS' THEN 1
                                     WHEN 'POSITION' THEN 2 ELSE 3 END,
                                 relationship.effective_start_date DESC,
                                 relationship.position_relationship_id DESC
                        LIMIT 1
                  ) current_parent ON TRUE
                  LEFT JOIN ppl_positions parent
                    ON parent.tenant_id = position.tenant_id
                   AND parent.position_id = current_parent.parent_position_id
                 WHERE position.tenant_id = ? AND position.public_id = ?
                   AND position.valid_from <= ?
                   AND (position.valid_to IS NULL OR position.valid_to >= ?)
                   AND position.position_status <> 'CLOSED'
                """, (result, ignored) -> new OrganizationScenarioRepository.PositionPlanningRecord(
                result.getLong("position_id"), result.getObject("public_id", UUID.class),
                result.getString("position_key"), result.getString("title"),
                result.getObject("organization_public_id", UUID.class),
                result.getString("organization_name"),
                result.getObject("parent_public_id", UUID.class), result.getString("parent_title"),
                result.getString("position_status"), result.getString("position_type"),
                result.getString("criticality"), result.getBigDecimal("budgeted_fte"),
                result.getBigDecimal("annual_cost_amount"), result.getString("cost_currency"),
                date(result, "availability_date"), date(result, "valid_from"),
                result.getInt("incumbent_count"), result.getInt("subordinate_count")),
                Date.valueOf(asOf), Date.valueOf(asOf), Date.valueOf(asOf), Date.valueOf(asOf),
                Date.valueOf(asOf), Date.valueOf(asOf), tenantId, publicId,
                Date.valueOf(asOf), Date.valueOf(asOf)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Position not found."));
    }

    public void applyMove(
            Long tenantId,
            OrganizationScenarioRepository.MoveRecord move,
            LocalDate effectiveDate,
            String scenarioKey,
            Long actorId) {
        OrganizationScenarioRepository.OrganizationRecord target = organization(tenantId, move.organizationId(), effectiveDate);
        OrganizationScenarioRepository.OrganizationRecord parent = organization(tenantId, move.newParentId(), effectiveDate);
        List<RelationshipSlice> current = jdbc.query("""
                SELECT organization_relationship_id, effective_start_date
                  FROM ppl_organization_relationships
                 WHERE tenant_id = ? AND child_organization_id = ?
                   AND relationship_type = 'SUPERVISORY' AND primary_relationship = TRUE
                   AND effective_start_date <= ?
                   AND (effective_end_date IS NULL OR effective_end_date >= ?)
                 ORDER BY effective_start_date DESC, organization_relationship_id DESC
                """, (result, ignored) -> new RelationshipSlice(
                result.getLong("organization_relationship_id"), date(result, "effective_start_date")),
                tenantId, target.internalId(), Date.valueOf(effectiveDate), Date.valueOf(effectiveDate));
        if (current.size() > 1) throw new IllegalStateException("Multiple effective primary parents exist.");
        if (!current.isEmpty()) {
            RelationshipSlice slice = current.get(0);
            if (!slice.effectiveStartDate().isBefore(effectiveDate)) {
                throw new IllegalStateException("A supervisory change already starts on the scenario date.");
            }
            jdbc.update("""
                    UPDATE ppl_organization_relationships
                       SET effective_end_date = ?, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = ?
                     WHERE tenant_id = ? AND organization_relationship_id = ?
                    """, Date.valueOf(effectiveDate.minusDays(1)), actorId,
                    tenantId, slice.relationshipId());
        }
        jdbc.update("""
                INSERT INTO ppl_organization_relationships (
                    tenant_id, child_organization_id, parent_organization_id,
                    relationship_type, primary_relationship, effective_start_date,
                    external_id, created_by, updated_by)
                VALUES (?, ?, ?, 'SUPERVISORY', TRUE, ?, ?, ?, ?)
                """, tenantId, target.internalId(), parent.internalId(),
                Date.valueOf(effectiveDate), scenarioKey + ":" + move.changeId(), actorId, actorId);
    }

    public void applyPositionMove(
            Long tenantId,
            OrganizationScenarioRepository.PositionMoveRecord move,
            LocalDate effectiveDate,
            String scenarioKey,
            Long actorId) {
        OrganizationScenarioRepository.PositionRecord target = position(tenantId, move.positionId(), effectiveDate);
        OrganizationScenarioRepository.PositionRecord parent = position(tenantId, move.newParentId(), effectiveDate);
        List<RelationshipSlice> current = jdbc.query("""
                SELECT position_relationship_id, effective_start_date
                  FROM ppl_position_relationships
                 WHERE tenant_id = ? AND child_position_id = ?
                   AND relationship_type = 'SUPERVISORY'
                   AND relationship_source = 'SCENARIO'
                   AND primary_relationship = TRUE
                   AND effective_start_date <= ?
                   AND (effective_end_date IS NULL OR effective_end_date >= ?)
                 ORDER BY effective_start_date DESC, position_relationship_id DESC
                """, (result, ignored) -> new RelationshipSlice(
                result.getLong("position_relationship_id"), date(result, "effective_start_date")),
                tenantId, target.internalId(), Date.valueOf(effectiveDate), Date.valueOf(effectiveDate));
        if (current.size() > 1) throw new IllegalStateException("Multiple scenario position parents exist.");
        if (!current.isEmpty()) {
            RelationshipSlice slice = current.get(0);
            if (!slice.effectiveStartDate().isBefore(effectiveDate)) {
                throw new IllegalStateException("A scenario position change already starts on this date.");
            }
            jdbc.update("""
                    UPDATE ppl_position_relationships
                       SET effective_end_date = ?, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = ?
                     WHERE tenant_id = ? AND position_relationship_id = ?
                    """, Date.valueOf(effectiveDate.minusDays(1)), actorId,
                    tenantId, slice.relationshipId());
        }
        jdbc.update("""
                INSERT INTO ppl_position_relationships (
                    tenant_id, child_position_id, parent_position_id,
                    relationship_type, primary_relationship, relationship_source,
                    effective_start_date, external_id, created_by, updated_by)
                VALUES (?, ?, ?, 'SUPERVISORY', TRUE, 'SCENARIO', ?, ?, ?, ?)
                """, tenantId, target.internalId(), parent.internalId(),
                Date.valueOf(effectiveDate), scenarioKey + ":" + move.changeId(), actorId, actorId);
    }

    public void applyPositionCreate(
            Long tenantId,
            OrganizationScenarioRepository.PositionCreateRecord create,
            LocalDate effectiveDate,
            String scenarioKey,
            Long actorId) {
        OrganizationScenarioRepository.OrganizationRecord organization = organization(
                tenantId, create.organizationId(), effectiveDate);
        OrganizationScenarioRepository.PositionRecord parent = position(tenantId, create.parentPositionId(), effectiveDate);
        jdbc.update("""
                INSERT INTO ppl_positions (
                    public_id, tenant_id, position_key, title, organization_id,
                    availability_date, position_status, reports_to_position_id,
                    position_type, criticality, budgeted_fte, annual_cost_amount,
                    cost_currency, external_id, valid_from, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, create.positionId(), tenantId, create.positionKey(), create.title(),
                organization.internalId(), Date.valueOf(create.availabilityDate()),
                parent.internalId(), create.positionType(), create.criticality(),
                create.budgetedFte(), create.annualCostAmount(), create.costCurrency(),
                scenarioKey + ":" + create.changeId(), Date.valueOf(effectiveDate), actorId, actorId);
        OrganizationScenarioRepository.PositionRecord inserted = position(tenantId, create.positionId(), effectiveDate);
        jdbc.update("""
                INSERT INTO ppl_position_relationships (
                    tenant_id, child_position_id, parent_position_id,
                    relationship_type, primary_relationship, relationship_source,
                    effective_start_date, external_id, created_by, updated_by)
                VALUES (?, ?, ?, 'SUPERVISORY', TRUE, 'SCENARIO', ?, ?, ?, ?)
                """, tenantId, inserted.internalId(), parent.internalId(),
                Date.valueOf(effectiveDate), scenarioKey + ":" + create.changeId(), actorId, actorId);
    }

    public void applyPositionClose(
            Long tenantId,
            OrganizationScenarioRepository.PositionCloseRecord close,
            LocalDate effectiveDate,
            Long actorId) {
        OrganizationScenarioRepository.PositionPlanningRecord position = positionPlanning(tenantId, close.positionId(), effectiveDate);
        if (position.incumbentCount() > 0) {
            throw new IllegalStateException("A position with an effective incumbent cannot be closed.");
        }
        if (position.subordinateCount() > 0) {
            throw new IllegalStateException("A position with subordinate positions cannot be closed.");
        }
        if (!position.validFrom().isBefore(effectiveDate)) {
            throw new IllegalStateException("The position cannot be closed on or before its start date.");
        }
        int futureRelationship = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM ppl_position_relationships
                 WHERE tenant_id = ? AND child_position_id = ?
                   AND relationship_type = 'SUPERVISORY'
                   AND effective_start_date >= ?
                   AND (effective_end_date IS NULL OR effective_end_date >= ?)
                """, Integer.class, tenantId, position.internalId(),
                Date.valueOf(effectiveDate), Date.valueOf(effectiveDate));
        if (futureRelationship > 0) {
            throw new IllegalStateException("A position relationship already starts on or after the closure date.");
        }
        jdbc.update("""
                UPDATE ppl_position_relationships
                   SET effective_end_date = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND child_position_id = ?
                   AND relationship_type = 'SUPERVISORY'
                   AND effective_start_date < ?
                   AND (effective_end_date IS NULL OR effective_end_date >= ?)
                """, Date.valueOf(effectiveDate.minusDays(1)), actorId,
                tenantId, position.internalId(), Date.valueOf(effectiveDate), Date.valueOf(effectiveDate));
        int updated = jdbc.update("""
                UPDATE ppl_positions
                   SET valid_to = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND position_id = ?
                   AND valid_from < ?
                   AND (valid_to IS NULL OR valid_to >= ?)
                """, Date.valueOf(effectiveDate.minusDays(1)), actorId,
                tenantId, position.internalId(), Date.valueOf(effectiveDate), Date.valueOf(effectiveDate));
        if (updated != 1) throw new IllegalStateException("The position changed before closure.");
    }

    public boolean publish(
            Long tenantId,
            UUID scenarioId,
            UUID validationRunId,
            Long actorId,
            long expectedVersion) {
        return jdbc.update("""
                UPDATE ppl_organization_scenarios
                   SET lifecycle_state = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                       published_by = ?, publication_validation_run_id = ?,
                       publication_evidence_state = 'BOUND', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                   AND lifecycle_state = 'APPROVED' AND version = ?
                """, actorId, validationRunId, actorId,
                tenantId, scenarioId, expectedVersion) == 1;
    }

}
