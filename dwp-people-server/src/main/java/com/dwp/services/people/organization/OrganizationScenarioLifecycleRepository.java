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


class OrganizationScenarioLifecycleRepository extends OrganizationScenarioJdbcRepository {
    OrganizationScenarioLifecycleRepository(JdbcTemplate jdbc) { super(jdbc); }

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

    public Optional<OrganizationScenarioRepository.ScenarioRecord> scenario(Long tenantId, UUID scenarioId) {
        return jdbc.query("""
                SELECT organization_scenario_id, scenario_key, name, baseline_date, effective_date,
                       baseline_fingerprint, lifecycle_state, owner_user_id, version
                 FROM ppl_organization_scenarios
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                """, (result, ignored) -> new OrganizationScenarioRepository.ScenarioRecord(
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
            OrganizationScenarioRepository.ScenarioRecord source,
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
            OrganizationScenarioRepository.ScenarioRecord scenario,
            OrganizationScenarioRepository.OrganizationRecord organization,
            OrganizationScenarioRepository.OrganizationRecord newParent,
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
            OrganizationScenarioRepository.ScenarioRecord scenario,
            OrganizationScenarioRepository.PositionRecord position,
            OrganizationScenarioRepository.PositionRecord newParent,
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
            OrganizationScenarioRepository.ScenarioRecord scenario,
            UUID positionId,
            OrganizationScenarioDtos.CreatePositionRequest request,
            OrganizationScenarioRepository.OrganizationRecord organization,
            OrganizationScenarioRepository.PositionRecord parent,
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
            OrganizationScenarioRepository.ScenarioRecord scenario,
            OrganizationScenarioRepository.PositionPlanningRecord position,
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
            OrganizationScenarioRepository.ScenarioRecord scenario,
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

    public Optional<OrganizationScenarioRepository.ApprovalRecord> approval(Long tenantId, UUID scenarioId) {
        return jdbc.query("""
                SELECT organization_scenario_approval_id, organization_scenario_id,
                       required_role_code, separation_of_duties, lifecycle_state,
                       requested_by, expires_at, version
                  FROM ppl_organization_scenario_approvals
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                 ORDER BY gate_order DESC LIMIT 1
                """, (result, ignored) -> new OrganizationScenarioRepository.ApprovalRecord(
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
            OrganizationScenarioRepository.ApprovalRecord approval,
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

}
