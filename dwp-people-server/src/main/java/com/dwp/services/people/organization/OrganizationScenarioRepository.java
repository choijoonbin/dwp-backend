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

@Repository
public class OrganizationScenarioRepository {

    private final JdbcTemplate jdbc;

    public OrganizationScenarioRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<OrganizationScenarioDtos.Scenario> scenarios(Long tenantId) {
        return jdbc.query("""
                SELECT organization_scenario_id, scenario_key, name, description,
                       source_scenario_id, baseline_date, effective_date,
                       lifecycle_state, owner_user_id,
                       submitted_at, published_at, publication_validation_run_id,
                       publication_evidence_state, version
                  FROM ppl_organization_scenarios
                 WHERE tenant_id = ?
                 ORDER BY CASE lifecycle_state
                              WHEN 'IN_REVIEW' THEN 1 WHEN 'DRAFT' THEN 2
                              WHEN 'APPROVED' THEN 3 ELSE 4 END,
                          updated_at DESC
                """, (result, ignored) -> scenario(tenantId, result), tenantId);
    }

    public Optional<ScenarioRecord> scenario(Long tenantId, UUID scenarioId) {
        return jdbc.query("""
                SELECT organization_scenario_id, scenario_key, name, baseline_date, effective_date,
                       baseline_fingerprint, lifecycle_state, owner_user_id, version
                  FROM ppl_organization_scenarios
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                """, (result, ignored) -> new ScenarioRecord(
                result.getObject("organization_scenario_id", UUID.class),
                result.getString("scenario_key"), result.getString("name"), date(result, "baseline_date"),
                date(result, "effective_date"), result.getString("baseline_fingerprint"),
                result.getString("lifecycle_state"), result.getLong("owner_user_id"),
                result.getLong("version")), tenantId, scenarioId).stream().findFirst();
    }

    public UUID createScenario(
            Long tenantId,
            OrganizationScenarioDtos.CreateScenarioRequest request,
            String baselineFingerprint,
            Long actorId) {
        UUID scenarioId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ppl_organization_scenarios (
                    organization_scenario_id, tenant_id, scenario_key, name, description,
                    baseline_date, effective_date, baseline_fingerprint, lifecycle_state,
                    owner_user_id, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                """, scenarioId, tenantId, request.scenarioKey().trim(), request.name().trim(),
                nullable(request.description()), Date.valueOf(request.baselineDate()),
                Date.valueOf(request.effectiveDate()), baselineFingerprint,
                actorId, actorId, actorId);
        return scenarioId;
    }

    public UUID cloneScenario(
            Long tenantId,
            ScenarioRecord source,
            OrganizationScenarioDtos.CloneScenarioRequest request,
            Long actorId) {
        UUID scenarioId = UUID.randomUUID();
        int created = jdbc.update("""
                INSERT INTO ppl_organization_scenarios (
                    organization_scenario_id, tenant_id, scenario_key, name, description,
                    baseline_date, effective_date, baseline_fingerprint, lifecycle_state,
                    owner_user_id, source_scenario_id, created_by, updated_by)
                SELECT ?, tenant_id, ?, ?, COALESCE(?, description),
                       baseline_date, ?, baseline_fingerprint, 'DRAFT',
                       ?, organization_scenario_id, ?, ?
                  FROM ppl_organization_scenarios
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                """, scenarioId, request.scenarioKey().trim(), request.name().trim(),
                nullable(request.description()), Date.valueOf(request.effectiveDate()),
                actorId, actorId, actorId, tenantId, source.scenarioId());
        if (created != 1) {
            throw new IllegalArgumentException("The source organization scenario no longer exists.");
        }
        jdbc.update("""
                INSERT INTO ppl_organization_scenario_changes (
                    tenant_id, organization_scenario_id, change_sequence, change_type,
                    payload_schema_version, target_kind, target_reference,
                    related_reference, effective_date, before_snapshot, after_snapshot,
                    estimated_headcount_delta, estimated_fte_delta, estimated_cost_delta,
                    cost_currency, validation_state, validation_message, created_by, updated_by)
                SELECT tenant_id, ?, change_sequence, change_type,
                       payload_schema_version, target_kind, target_reference,
                       related_reference, ?, before_snapshot, after_snapshot,
                       estimated_headcount_delta, estimated_fte_delta, estimated_cost_delta,
                       cost_currency, validation_state, validation_message, ?, ?
                  FROM ppl_organization_scenario_changes
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                 ORDER BY change_sequence
                """, scenarioId, Date.valueOf(request.effectiveDate()), actorId, actorId,
                tenantId, source.scenarioId());
        return scenarioId;
    }

    public boolean addOrganizationMove(
            Long tenantId,
            ScenarioRecord scenario,
            OrganizationRecord organization,
            OrganizationRecord newParent,
            Long actorId,
            long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE ppl_organization_scenarios
                   SET version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                   AND lifecycle_state = 'DRAFT' AND version = ?
                """, actorId, tenantId, scenario.scenarioId(), expectedVersion);
        if (updated != 1) return false;
        Integer sequence = jdbc.queryForObject("""
                SELECT COALESCE(MAX(change_sequence), 0) + 1
                  FROM ppl_organization_scenario_changes
                 WHERE organization_scenario_id = ?
                """, Integer.class, scenario.scenarioId());
        jdbc.update("""
                INSERT INTO ppl_organization_scenario_changes (
                    tenant_id, organization_scenario_id, change_sequence, change_type,
                    payload_schema_version, target_kind, target_reference,
                    related_reference, effective_date,
                    before_snapshot, after_snapshot, validation_state,
                    created_by, updated_by)
                VALUES (?, ?, ?, 'MOVE_ORGANIZATION',
                    (SELECT payload_schema_version
                       FROM ppl_organization_change_type_catalog
                      WHERE change_type = 'MOVE_ORGANIZATION'),
                    'ORGANIZATION', ?, ?, ?,
                    jsonb_build_object('parentOrganizationId', ?, 'parentName', ?),
                    jsonb_build_object('parentOrganizationId', ?, 'parentName', ?),
                    'VALID', ?, ?)
                """, tenantId, scenario.scenarioId(), sequence,
                organization.publicId().toString(), newParent.publicId().toString(),
                Date.valueOf(scenario.effectiveDate()),
                organization.parentPublicId() == null ? null : organization.parentPublicId().toString(),
                organization.parentName(), newParent.publicId().toString(), newParent.name(),
                actorId, actorId);
        return true;
    }

    public boolean addPositionMove(
            Long tenantId,
            ScenarioRecord scenario,
            PositionRecord position,
            PositionRecord newParent,
            Long actorId,
            long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE ppl_organization_scenarios
                   SET version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                   AND lifecycle_state = 'DRAFT' AND version = ?
                """, actorId, tenantId, scenario.scenarioId(), expectedVersion);
        if (updated != 1) return false;
        Integer sequence = jdbc.queryForObject("""
                SELECT COALESCE(MAX(change_sequence), 0) + 1
                  FROM ppl_organization_scenario_changes
                 WHERE organization_scenario_id = ?
                """, Integer.class, scenario.scenarioId());
        jdbc.update("""
                INSERT INTO ppl_organization_scenario_changes (
                    tenant_id, organization_scenario_id, change_sequence, change_type,
                    payload_schema_version, target_kind, target_reference,
                    related_reference, effective_date,
                    before_snapshot, after_snapshot, validation_state,
                    created_by, updated_by)
                VALUES (?, ?, ?, 'MOVE_POSITION',
                    (SELECT payload_schema_version
                       FROM ppl_organization_change_type_catalog
                      WHERE change_type = 'MOVE_POSITION'),
                    'POSITION', ?, ?, ?,
                    jsonb_build_object('parentPositionId', ?, 'parentTitle', ?),
                    jsonb_build_object('parentPositionId', ?, 'parentTitle', ?),
                    'VALID', ?, ?)
                """, tenantId, scenario.scenarioId(), sequence,
                position.publicId().toString(), newParent.publicId().toString(),
                Date.valueOf(scenario.effectiveDate()),
                position.parentPublicId() == null ? null : position.parentPublicId().toString(),
                position.parentTitle(), newParent.publicId().toString(), newParent.title(),
                actorId, actorId);
        return true;
    }

    public boolean addPositionCreate(
            Long tenantId,
            ScenarioRecord scenario,
            UUID positionId,
            OrganizationScenarioDtos.CreatePositionRequest request,
            OrganizationRecord organization,
            PositionRecord parent,
            Long actorId,
            long expectedVersion) {
        if (!advanceDraft(tenantId, scenario.scenarioId(), actorId, expectedVersion)) return false;
        int sequence = nextSequence(scenario.scenarioId());
        jdbc.update("""
                INSERT INTO ppl_organization_scenario_changes (
                    tenant_id, organization_scenario_id, change_sequence, change_type,
                    payload_schema_version, target_kind, target_reference,
                    related_reference, effective_date,
                    before_snapshot, after_snapshot, estimated_fte_delta,
                    estimated_cost_delta, cost_currency, validation_state,
                    created_by, updated_by)
                VALUES (?, ?, ?, 'CREATE_POSITION',
                    (SELECT payload_schema_version
                       FROM ppl_organization_change_type_catalog
                      WHERE change_type = 'CREATE_POSITION'),
                    'POSITION', ?, ?, ?, '{}'::jsonb,
                    jsonb_build_object(
                        'positionKey', ?, 'title', ?,
                        'organizationId', ?, 'organizationName', ?,
                        'reportsToPositionId', ?, 'reportsToPositionTitle', ?,
                        'positionType', ?, 'criticality', ?, 'budgetedFte', ?,
                        'annualCostAmount', ?, 'costCurrency', ?, 'availabilityDate', ?),
                    ?, ?, ?, 'VALID', ?, ?)
                """, tenantId, scenario.scenarioId(), sequence,
                positionId.toString(), parent.publicId().toString(),
                Date.valueOf(scenario.effectiveDate()),
                request.positionKey().trim().toUpperCase(), request.title().trim(),
                organization.publicId().toString(), organization.name(),
                parent.publicId().toString(), parent.title(), request.positionType(),
                request.criticality(), request.budgetedFte(), request.annualCostAmount(),
                request.costCurrency(), request.availabilityDate().toString(),
                request.budgetedFte(), request.annualCostAmount(), request.costCurrency(),
                actorId, actorId);
        return true;
    }

    public boolean addPositionClose(
            Long tenantId,
            ScenarioRecord scenario,
            PositionPlanningRecord position,
            Long actorId,
            long expectedVersion) {
        if (!advanceDraft(tenantId, scenario.scenarioId(), actorId, expectedVersion)) return false;
        int sequence = nextSequence(scenario.scenarioId());
        jdbc.update("""
                INSERT INTO ppl_organization_scenario_changes (
                    tenant_id, organization_scenario_id, change_sequence, change_type,
                    payload_schema_version, target_kind, target_reference, effective_date,
                    before_snapshot, after_snapshot, estimated_fte_delta,
                    estimated_cost_delta, cost_currency, validation_state,
                    created_by, updated_by)
                VALUES (?, ?, ?, 'CLOSE_POSITION',
                    (SELECT payload_schema_version
                       FROM ppl_organization_change_type_catalog
                      WHERE change_type = 'CLOSE_POSITION'),
                    'POSITION', ?, ?,
                    jsonb_build_object(
                        'positionKey', ?, 'title', ?,
                        'organizationId', ?, 'organizationName', ?,
                        'reportsToPositionId', ?, 'reportsToPositionTitle', ?,
                        'positionType', ?, 'criticality', ?, 'budgetedFte', ?,
                        'annualCostAmount', ?, 'costCurrency', ?, 'availabilityDate', ?),
                    jsonb_build_object('positionKey', ?, 'title', ?, 'closed', TRUE),
                    ?, ?, ?, 'VALID', ?, ?)
                """, tenantId, scenario.scenarioId(), sequence,
                position.publicId().toString(), Date.valueOf(scenario.effectiveDate()),
                position.key(), position.title(), position.organizationPublicId().toString(),
                position.organizationName(),
                position.parentPublicId() == null ? null : position.parentPublicId().toString(),
                position.parentTitle(), position.type(), position.criticality(),
                position.budgetedFte(), position.annualCostAmount(), position.costCurrency(),
                position.availabilityDate() == null ? null : position.availabilityDate().toString(),
                position.key(), position.title(), position.budgetedFte().negate(),
                position.annualCostAmount() == null ? null : position.annualCostAmount().negate(),
                position.costCurrency(), actorId, actorId);
        return true;
    }

    public boolean submit(
            Long tenantId,
            ScenarioRecord scenario,
            String reason,
            UUID validationRunId,
            Long actorId,
            long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE ppl_organization_scenarios
                   SET lifecycle_state = 'IN_REVIEW', submitted_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                   AND lifecycle_state = 'DRAFT' AND version = ?
                   AND EXISTS (
                       SELECT 1 FROM ppl_organization_scenario_changes change
                        WHERE change.organization_scenario_id = ppl_organization_scenarios.organization_scenario_id
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM ppl_organization_scenario_changes change
                        WHERE change.organization_scenario_id = ppl_organization_scenarios.organization_scenario_id
                          AND change.validation_state = 'BLOCKED')
                """, actorId, tenantId, scenario.scenarioId(), expectedVersion);
        if (updated != 1) return false;
        jdbc.update("""
                INSERT INTO ppl_organization_scenario_approvals (
                    tenant_id, organization_scenario_id, gate_key, required_role_code,
                    separation_of_duties, lifecycle_state, requested_by, request_reason,
                    request_validation_run_id, evidence_binding_state)
                VALUES (?, ?, 'ORG_DESIGN_REVIEW', 'HR_ADMIN', TRUE, 'PENDING', ?, ?, ?, 'BOUND')
                ON CONFLICT (organization_scenario_id, gate_key) DO NOTHING
                """, tenantId, scenario.scenarioId(), actorId, reason.trim(), validationRunId);
        return true;
    }

    public Optional<ApprovalRecord> approval(Long tenantId, UUID scenarioId) {
        return jdbc.query("""
                SELECT organization_scenario_approval_id, organization_scenario_id,
                       required_role_code, separation_of_duties, lifecycle_state,
                       requested_by, expires_at, version
                  FROM ppl_organization_scenario_approvals
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                 ORDER BY gate_order DESC LIMIT 1
                """, (result, ignored) -> new ApprovalRecord(
                result.getObject("organization_scenario_approval_id", UUID.class),
                result.getObject("organization_scenario_id", UUID.class),
                result.getString("required_role_code"),
                result.getBoolean("separation_of_duties"),
                effectiveApprovalState(result), result.getLong("requested_by"),
                instant(result, "expires_at"), result.getLong("version")),
                tenantId, scenarioId).stream().findFirst();
    }

    public boolean decide(
            Long tenantId,
            ApprovalRecord approval,
            String decision,
            String reason,
            UUID validationRunId,
            Long actorId,
            long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE ppl_organization_scenario_approvals
                   SET lifecycle_state = ?, decided_by = ?, decision_reason = ?,
                       decided_at = CURRENT_TIMESTAMP, decision_validation_run_id = ?,
                       evidence_binding_state = 'BOUND', version = version + 1
                 WHERE tenant_id = ? AND organization_scenario_approval_id = ?
                   AND lifecycle_state = 'PENDING' AND version = ?
                   AND expires_at > CURRENT_TIMESTAMP
                """, decision, actorId, reason.trim(), validationRunId, tenantId,
                approval.approvalId(), expectedVersion);
        if (updated != 1) return false;
        jdbc.update("""
                UPDATE ppl_organization_scenarios
                   SET lifecycle_state = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                   AND lifecycle_state = 'IN_REVIEW'
                """, "APPROVED".equals(decision) ? "APPROVED" : "REJECTED",
                actorId, tenantId, approval.scenarioId());
        return true;
    }

    public boolean cancel(
            Long tenantId,
            UUID scenarioId,
            Long actorId,
            String reason,
            long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE ppl_organization_scenarios
                   SET lifecycle_state = 'CANCELLED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                   AND lifecycle_state IN ('DRAFT', 'IN_REVIEW') AND version = ?
                """, actorId, tenantId, scenarioId, expectedVersion);
        if (updated != 1) return false;
        jdbc.update("""
                UPDATE ppl_organization_scenario_approvals
                   SET lifecycle_state = 'CANCELLED', decided_by = ?,
                       decision_reason = ?, decided_at = CURRENT_TIMESTAMP,
                       version = version + 1
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                   AND lifecycle_state = 'PENDING'
                """, actorId, reason.trim(), tenantId, scenarioId);
        return true;
    }

    public List<MoveRecord> moves(Long tenantId, UUID scenarioId) {
        return jdbc.query("""
                SELECT organization_scenario_change_id, target_reference, related_reference
                  FROM ppl_organization_scenario_changes
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                   AND change_type = 'MOVE_ORGANIZATION'
                 ORDER BY change_sequence
                """, (result, ignored) -> new MoveRecord(
                result.getObject("organization_scenario_change_id", UUID.class),
                UUID.fromString(result.getString("target_reference")),
                UUID.fromString(result.getString("related_reference"))), tenantId, scenarioId);
    }

    public List<PositionMoveRecord> positionMoves(Long tenantId, UUID scenarioId) {
        return jdbc.query("""
                SELECT organization_scenario_change_id, target_reference, related_reference
                  FROM ppl_organization_scenario_changes
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                   AND change_type = 'MOVE_POSITION'
                 ORDER BY change_sequence
                """, (result, ignored) -> new PositionMoveRecord(
                result.getObject("organization_scenario_change_id", UUID.class),
                UUID.fromString(result.getString("target_reference")),
                UUID.fromString(result.getString("related_reference"))), tenantId, scenarioId);
    }

    public List<PositionCreateRecord> positionCreates(Long tenantId, UUID scenarioId) {
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
                """, (result, ignored) -> new PositionCreateRecord(
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

    public List<PositionCloseRecord> positionCloses(Long tenantId, UUID scenarioId) {
        return jdbc.query("""
                SELECT organization_scenario_change_id, target_reference
                  FROM ppl_organization_scenario_changes
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                   AND change_type = 'CLOSE_POSITION'
                 ORDER BY change_sequence
                """, (result, ignored) -> new PositionCloseRecord(
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

    public OrganizationRecord organization(Long tenantId, UUID publicId, LocalDate asOf) {
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
                """, (result, ignored) -> new OrganizationRecord(
                result.getLong("organization_id"), result.getObject("public_id", UUID.class),
                result.getString("name"), result.getObject("parent_public_id", UUID.class),
                result.getString("parent_name")), Date.valueOf(asOf), Date.valueOf(asOf),
                tenantId, publicId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Organization not found."));
    }

    public PositionRecord position(Long tenantId, UUID publicId, LocalDate asOf) {
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
                """, (result, ignored) -> new PositionRecord(
                result.getLong("position_id"), result.getObject("public_id", UUID.class),
                result.getString("title"), result.getObject("parent_public_id", UUID.class),
                result.getString("parent_title")), Date.valueOf(asOf), Date.valueOf(asOf),
                tenantId, publicId, Date.valueOf(asOf), Date.valueOf(asOf)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Position not found."));
    }

    public PositionPlanningRecord positionPlanning(Long tenantId, UUID publicId, LocalDate asOf) {
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
                """, (result, ignored) -> new PositionPlanningRecord(
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
            MoveRecord move,
            LocalDate effectiveDate,
            String scenarioKey,
            Long actorId) {
        OrganizationRecord target = organization(tenantId, move.organizationId(), effectiveDate);
        OrganizationRecord parent = organization(tenantId, move.newParentId(), effectiveDate);
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
            PositionMoveRecord move,
            LocalDate effectiveDate,
            String scenarioKey,
            Long actorId) {
        PositionRecord target = position(tenantId, move.positionId(), effectiveDate);
        PositionRecord parent = position(tenantId, move.newParentId(), effectiveDate);
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
            PositionCreateRecord create,
            LocalDate effectiveDate,
            String scenarioKey,
            Long actorId) {
        OrganizationRecord organization = organization(
                tenantId, create.organizationId(), effectiveDate);
        PositionRecord parent = position(tenantId, create.parentPositionId(), effectiveDate);
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
        PositionRecord inserted = position(tenantId, create.positionId(), effectiveDate);
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
            PositionCloseRecord close,
            LocalDate effectiveDate,
            Long actorId) {
        PositionPlanningRecord position = positionPlanning(tenantId, close.positionId(), effectiveDate);
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

    private OrganizationScenarioDtos.Scenario scenario(Long tenantId, ResultSet result)
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

    public UUID recordValidation(
            Long tenantId,
            ScenarioRecord scenario,
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

    private OrganizationScenarioDtos.Approval approvalSummary(Long tenantId, UUID scenarioId) {
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

    private LocalDate date(ResultSet result, String column) throws SQLException {
        Date value = result.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private String effectiveApprovalState(ResultSet result) throws SQLException {
        String state = result.getString("lifecycle_state");
        Instant expiresAt = instant(result, "expires_at");
        return "PENDING".equals(state) && expiresAt != null && !expiresAt.isAfter(Instant.now())
                ? "EXPIRED"
                : state;
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean advanceDraft(
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

    private int nextSequence(UUID scenarioId) {
        Integer sequence = jdbc.queryForObject("""
                SELECT COALESCE(MAX(change_sequence), 0) + 1
                  FROM ppl_organization_scenario_changes
                 WHERE organization_scenario_id = ?
                """, Integer.class, scenarioId);
        return sequence == null ? 1 : sequence;
    }

    public record ScenarioRecord(
            UUID scenarioId,
            String scenarioKey,
            String name,
            LocalDate baselineDate,
            LocalDate effectiveDate,
            String baselineFingerprint,
            String lifecycleState,
            Long ownerUserId,
            long version) {
    }

    public record ApprovalRecord(
            UUID approvalId,
            UUID scenarioId,
            String requiredRoleCode,
            boolean separationOfDuties,
            String lifecycleState,
            Long requestedBy,
            Instant expiresAt,
            long version) {
    }

    public record OrganizationRecord(
            long internalId,
            UUID publicId,
            String name,
            UUID parentPublicId,
            String parentName) {
    }

    public record MoveRecord(UUID changeId, UUID organizationId, UUID newParentId) {
    }

    public record PositionRecord(
            long internalId,
            UUID publicId,
            String title,
            UUID parentPublicId,
            String parentTitle) {
    }

    public record PositionMoveRecord(UUID changeId, UUID positionId, UUID newParentId) {
    }

    public record PositionCreateRecord(
            UUID changeId,
            UUID positionId,
            String positionKey,
            String title,
            UUID organizationId,
            UUID parentPositionId,
            String positionType,
            String criticality,
            BigDecimal budgetedFte,
            BigDecimal annualCostAmount,
            String costCurrency,
            LocalDate availabilityDate) {
    }

    public record PositionCloseRecord(UUID changeId, UUID positionId) {
    }

    public record PositionPlanningRecord(
            long internalId,
            UUID publicId,
            String key,
            String title,
            UUID organizationPublicId,
            String organizationName,
            UUID parentPublicId,
            String parentTitle,
            String status,
            String type,
            String criticality,
            BigDecimal budgetedFte,
            BigDecimal annualCostAmount,
            String costCurrency,
            LocalDate availabilityDate,
            LocalDate validFrom,
            int incumbentCount,
            int subordinateCount) {
    }

    private record RelationshipSlice(long relationshipId, LocalDate effectiveStartDate) {
    }
}
