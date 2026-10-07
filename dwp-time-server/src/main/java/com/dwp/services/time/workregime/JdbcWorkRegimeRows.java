package com.dwp.services.time.workregime;

import com.dwp.services.time.workregime.WorkRegimeModels.ArrangementKind;
import com.dwp.services.time.workregime.WorkRegimeModels.EffectivePeriod;
import com.dwp.services.time.workregime.WorkRegimeModels.LocalSegment;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyCandidate;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyState;
import com.dwp.services.time.workregime.WorkRegimeModels.WorkRegimeRevision;
import com.dwp.services.time.workregime.WorkRegimeRepository.TargetBindingEvidence;
import com.dwp.services.time.workregime.WorkRegimeRepository.WorkPlanRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** JDBC select projections and row hydration shared by work-regime reads. */
final class JdbcWorkRegimeRows {

    static final String PLAN_COLUMNS = """
            SELECT wr.work_regime_version_id,
                   wr.tenant_id,
                   wr.public_id AS regime_public_id,
                   wr.revision AS regime_revision,
                   wr.version AS regime_version,
                   wr.lifecycle_state AS regime_state,
                   wr.author_actor_id,
                   wr.approval_actor_id,
                   wr.scope_public_ref,
                   wr.effective_from AS regime_from,
                   wr.effective_to AS regime_to,
                   wr.template_digest,
                   wr.display_name,
                   wr.arrangement_kind,
                   wr.extension_schema_ref,
                   wr.scope_type,
                   wr.rule_pack_public_id,
                   rp.jurisdiction_country,
                   wr.policy_revision,
                   wr.resolution_digest,
                   a.public_id AS assignment_public_id,
                   a.worker_public_id,
                   a.people_assignment_public_id,
                   a.people_assignment_revision,
                   a.effective_from AS assignment_from,
                   a.effective_to AS assignment_to,
                   a.zone_id,
                   te.population_public_id AS target_population_public_id,
                   te.population_revision AS target_population_revision,
                   te.membership_revision AS target_membership_revision,
                   te.author_actor_id AS target_author_actor_id,
                   te.author_gateway_scope_key,
                   te.author_grant_revision,
                   te.population_digest,
                   te.membership_digest,
                   te.grant_digest,
                   te.verified_at AS target_verified_at
              FROM tim_work_regime_versions wr
              JOIN tim_work_plan_assignments a
                ON a.tenant_id = wr.tenant_id
               AND a.work_regime_version_id = wr.work_regime_version_id
               AND a.policy_revision = wr.policy_revision
              JOIN tim_rule_pack_versions rp
                ON rp.tenant_id = wr.tenant_id
               AND rp.public_id = wr.rule_pack_public_id
               AND rp.policy_revision = wr.policy_revision
              JOIN tim_work_plan_target_evidence te
                ON te.tenant_id = a.tenant_id
               AND te.work_plan_assignment_id = a.work_plan_assignment_id
            """;

    static final String SEGMENTS_SQL = """
            SELECT iso_day_of_week, segment_key, segment_sequence, segment_kind,
                   local_start_time, local_end_time, end_day_offset, dst_overlap_policy
              FROM tim_work_regime_segments
             WHERE tenant_id = ? AND work_regime_version_id = ?
             ORDER BY iso_day_of_week, segment_sequence
            """;

    private JdbcWorkRegimeRows() { }

    static PlanRow mapPlanRow(ResultSet row, int number) throws SQLException {
        return new PlanRow(
                row.getLong("work_regime_version_id"), row.getLong("tenant_id"),
                uuid(row, "regime_public_id"), row.getLong("regime_revision"),
                row.getLong("regime_version"), PolicyState.valueOf(row.getString("regime_state")),
                row.getLong("author_actor_id"), nullableLong(row, "approval_actor_id"),
                row.getString("scope_public_ref"), period(row, "regime_from", "regime_to"),
                row.getString("template_digest").trim(), row.getString("display_name"),
                ArrangementKind.valueOf(row.getString("arrangement_kind")),
                row.getString("extension_schema_ref"),
                WorkRegimeModels.ScopeType.valueOf(row.getString("scope_type")),
                uuid(row, "rule_pack_public_id"), row.getString("jurisdiction_country").trim(),
                row.getLong("policy_revision"), row.getString("resolution_digest").trim(),
                uuid(row, "assignment_public_id"), uuid(row, "worker_public_id"),
                uuid(row, "people_assignment_public_id"), row.getLong("people_assignment_revision"),
                period(row, "assignment_from", "assignment_to"), row.getString("zone_id"),
                new TargetBindingEvidence(
                        uuid(row, "target_population_public_id"),
                        row.getLong("target_population_revision"),
                        uuid(row, "worker_public_id"), uuid(row, "people_assignment_public_id"),
                        row.getLong("people_assignment_revision"),
                        row.getLong("target_membership_revision"),
                        row.getLong("target_author_actor_id"),
                        row.getString("author_gateway_scope_key"),
                        row.getLong("author_grant_revision"),
                        row.getString("population_digest").trim(),
                        row.getString("membership_digest").trim(),
                        row.getString("grant_digest").trim(),
                        row.getTimestamp("target_verified_at").toInstant()));
    }

    static AssignmentRow mapAssignment(ResultSet row, int number) throws SQLException {
        return new AssignmentRow(
                row.getLong("work_plan_assignment_id"), uuid(row, "public_id"),
                uuid(row, "worker_public_id"), uuid(row, "people_assignment_public_id"),
                row.getLong("people_assignment_revision"), period(row, "effective_from", "effective_to"),
                row.getString("zone_id"), row.getLong("work_regime_version_id"),
                uuid(row, "regime_public_id"), row.getLong("regime_revision"));
    }

    static WorkRegimeRevision mapRevision(ResultSet row, int number) throws SQLException {
        return new WorkRegimeRevision(
                row.getLong("tenant_id"), uuid(row, "public_id"), row.getLong("revision"),
                row.getLong("version"), PolicyState.valueOf(row.getString("lifecycle_state")),
                row.getLong("author_actor_id"), nullableLong(row, "approval_actor_id"),
                row.getString("scope_public_ref"), period(row, "effective_from", "effective_to"),
                row.getString("template_digest").trim());
    }

    static PolicyCandidate mapPolicyCandidate(ResultSet row, int number) throws SQLException {
        return new PolicyCandidate(
                row.getLong("tenant_id"), uuid(row, "public_id"), row.getLong("revision"),
                ArrangementKind.valueOf(row.getString("arrangement_kind")),
                WorkRegimeModels.ScopeType.valueOf(row.getString("scope_type")),
                row.getString("scope_public_ref"), row.getInt("precedence_priority"),
                period(row, "effective_from", "effective_to"), uuid(row, "rule_pack_public_id"),
                row.getString("jurisdiction_country").trim(), row.getLong("policy_revision"),
                PolicyState.valueOf(row.getString("lifecycle_state")),
                row.getLong("author_actor_id"), row.getString("template_digest").trim());
    }

    static EffectivePeriod period(ResultSet row, String from, String to) throws SQLException {
        return new EffectivePeriod(
                row.getObject(from, LocalDate.class), row.getObject(to, LocalDate.class));
    }

    static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    static <T> T exactlyOne(List<T> rows, String label, Object publicId) {
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one " + label + " for " + publicId + "; found " + rows.size());
        }
        return rows.getFirst();
    }

    record PlanRow(
            long internalId, long tenantId, UUID publicId, long revision, long version,
            PolicyState state, long authorActorId, Long approvalActorId, String scopeRef,
            EffectivePeriod regimePeriod, String artifactDigest, String displayName,
            ArrangementKind arrangementKind, String extensionCode,
            WorkRegimeModels.ScopeType scopeType, UUID rulePackPublicId, String jurisdiction,
            long policyRevision, String resolutionDigest, UUID assignmentPublicId,
            UUID workerPublicId, UUID peopleAssignmentPublicId, long peopleAssignmentRevision,
            EffectivePeriod assignmentPeriod, String zoneId,
            TargetBindingEvidence targetBindingEvidence) {

        WorkPlanRecord toRecord(List<LocalSegment> segments) {
            return new WorkPlanRecord(
                    new WorkRegimeRevision(
                            tenantId, publicId, revision, version, state, authorActorId,
                            approvalActorId, scopeRef, regimePeriod, artifactDigest),
                    displayName, arrangementKind, extensionCode, scopeType, rulePackPublicId,
                    jurisdiction, policyRevision, resolutionDigest, zoneId, assignmentPublicId,
                    workerPublicId, peopleAssignmentPublicId, peopleAssignmentRevision,
                    assignmentPeriod, targetBindingEvidence, segments);
        }
    }

    record AssignmentRow(
            long internalId, UUID assignmentPublicId, UUID workerPublicId,
            UUID peopleAssignmentPublicId, long peopleAssignmentRevision,
            EffectivePeriod period, String zoneId, long workRegimeVersionId,
            UUID regimePublicId, long regimeRevision) { }

    record WorkerAssignmentIdentity(UUID workerPublicId, UUID peopleAssignmentPublicId) { }
}
