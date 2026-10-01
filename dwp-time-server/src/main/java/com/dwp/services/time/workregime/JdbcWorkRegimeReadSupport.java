package com.dwp.services.time.workregime;

import com.dwp.services.time.workregime.WorkRegimeModels.ArrangementKind;
import com.dwp.services.time.workregime.WorkRegimeModels.AssignmentPlan;
import com.dwp.services.time.workregime.WorkRegimeModels.DstOverlapPolicy;
import com.dwp.services.time.workregime.WorkRegimeModels.EffectivePeriod;
import com.dwp.services.time.workregime.WorkRegimeModels.LocalSegment;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyCandidate;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyState;
import com.dwp.services.time.workregime.WorkRegimeModels.RulePack;
import com.dwp.services.time.workregime.WorkRegimeModels.RulePackState;
import com.dwp.services.time.workregime.WorkRegimeModels.ScheduleTemplate;
import com.dwp.services.time.workregime.WorkRegimeModels.SegmentKind;
import com.dwp.services.time.workregime.WorkRegimeModels.WorkRegimeRevision;
import com.dwp.services.time.workregime.WorkRegimeRepository.WorkPlanRecord;
import com.dwp.services.time.workregime.WorkRegimeRepository.TargetBindingEvidence;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Read and hydration support for {@link JdbcWorkRegimeRepository}.
 *
 * <p>This helper deliberately does not own transactions. Every invocation is made by the owner
 * repository after it has opened a transaction and bound the tenant-local PostgreSQL RLS setting.
 */
final class JdbcWorkRegimeReadSupport {

    private static final String PLAN_COLUMNS = """
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

    private static final String SEGMENTS_SQL = """
            SELECT iso_day_of_week,
                   segment_key,
                   segment_sequence,
                   segment_kind,
                   local_start_time,
                   local_end_time,
                   end_day_offset,
                   dst_overlap_policy
              FROM tim_work_regime_segments
             WHERE tenant_id = ?
               AND work_regime_version_id = ?
             ORDER BY iso_day_of_week, segment_sequence
            """;

    private final JdbcTemplate jdbc;

    JdbcWorkRegimeReadSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<WorkPlanRecord> findEffective(long tenantId, LocalDate effectiveOn) {
        return hydrate(jdbc.query(
                PLAN_COLUMNS + """
                     WHERE wr.tenant_id = ?
                       AND wr.lifecycle_state IN (
                           'DRAFT', 'VALIDATED', 'SIMULATED',
                           'IN_REVIEW', 'APPROVED', 'PUBLISHED'
                       )
                       AND a.lifecycle_state IN ('DRAFT', 'PUBLISHED')
                       AND wr.effective_from <= ?
                       AND (wr.effective_to IS NULL OR wr.effective_to > ?)
                       AND a.effective_from <= ?
                       AND (a.effective_to IS NULL OR a.effective_to > ?)
                     ORDER BY wr.scope_type, wr.precedence_priority DESC,
                              wr.revision DESC, a.public_id
                    """,
                JdbcWorkRegimeReadSupport::mapPlanRow,
                tenantId,
                effectiveOn,
                effectiveOn,
                effectiveOn,
                effectiveOn));
    }

    List<WorkPlanRecord> findEffective(
            long tenantId, UUID populationPublicId, LocalDate effectiveOn) {
        return hydrate(jdbc.query(
                PLAN_COLUMNS + currentTargetJoins() + """
                     WHERE wr.tenant_id = ?
                       AND te.population_public_id = ?
                       AND wr.scope_type = 'POPULATION'
                       AND wr.scope_public_ref = p.scope_public_ref
                       AND wr.author_actor_id = te.author_actor_id
                       AND wr.lifecycle_state IN (
                           'DRAFT', 'VALIDATED', 'SIMULATED',
                           'IN_REVIEW', 'APPROVED', 'PUBLISHED'
                       )
                       AND a.lifecycle_state IN ('DRAFT', 'PUBLISHED')
                       AND wr.effective_from <= ?
                       AND (wr.effective_to IS NULL OR wr.effective_to > ?)
                       AND a.effective_from <= ?
                       AND (a.effective_to IS NULL OR a.effective_to > ?)
                     ORDER BY wr.precedence_priority DESC, wr.revision DESC, a.public_id
                     FOR SHARE OF p, m
                    """,
                JdbcWorkRegimeReadSupport::mapPlanRow,
                tenantId, populationPublicId,
                effectiveOn, effectiveOn, effectiveOn, effectiveOn));
    }

    Optional<WorkPlanRecord> findByPublicId(long tenantId, UUID publicId) {
        List<WorkPlanRecord> plans = findPlanRows(tenantId, publicId, null);
        if (plans.size() > 1) {
            throw new IllegalStateException(
                    "Work-regime public id resolves to multiple assignments: " + publicId);
        }
        return plans.stream().findFirst();
    }

    Optional<WorkPlanRecord> findByPublicId(
            long tenantId, UUID publicId, UUID populationPublicId) {
        List<WorkPlanRecord> plans = findPlanRows(
                tenantId, publicId, populationPublicId);
        if (plans.size() > 1) {
            throw new IllegalStateException(
                    "Work-regime public id resolves to multiple assignments: " + publicId);
        }
        return plans.stream().findFirst();
    }

    List<RulePack> findRulePacks(long tenantId, String jurisdiction, long policyRevision) {
        return jdbc.query("""
                SELECT tenant_id,
                       public_id,
                       jurisdiction_country,
                       policy_revision,
                       lifecycle_state,
                       effective_from,
                       effective_to,
                       signature_verified,
                       mandatory_bound_digest
                  FROM tim_rule_pack_versions
                 WHERE tenant_id = ?
                   AND jurisdiction_country = ?
                   AND policy_revision = ?
                 ORDER BY jurisdiction_subdivision, public_id
                """, (row, number) -> new RulePack(
                    row.getLong("tenant_id"),
                    uuid(row, "public_id"),
                    row.getString("jurisdiction_country").trim(),
                    row.getLong("policy_revision"),
                    RulePackState.valueOf(row.getString("lifecycle_state")),
                    period(row, "effective_from", "effective_to"),
                    row.getBoolean("signature_verified"),
                    row.getString("mandatory_bound_digest").trim()),
                tenantId,
                jurisdiction,
                policyRevision);
    }

    List<PolicyCandidate> findPolicyCandidates(
            long tenantId, String jurisdiction, long policyRevision) {
        return jdbc.query("""
                SELECT wr.tenant_id,
                       wr.public_id,
                       wr.revision,
                       wr.arrangement_kind,
                       wr.scope_type,
                       wr.scope_public_ref,
                       wr.precedence_priority,
                       wr.effective_from,
                       wr.effective_to,
                       wr.rule_pack_public_id,
                       rp.jurisdiction_country,
                       wr.policy_revision,
                       wr.lifecycle_state,
                       wr.author_actor_id,
                       wr.template_digest
                  FROM tim_work_regime_versions wr
                  JOIN tim_rule_pack_versions rp
                    ON rp.tenant_id = wr.tenant_id
                   AND rp.public_id = wr.rule_pack_public_id
                   AND rp.policy_revision = wr.policy_revision
                 WHERE wr.tenant_id = ?
                   AND rp.jurisdiction_country = ?
                   AND wr.policy_revision = ?
                 ORDER BY wr.public_id
                """, JdbcWorkRegimeReadSupport::mapPolicyCandidate,
                tenantId,
                jurisdiction,
                policyRevision);
    }

    List<PolicyCandidate> findPolicyCandidates(
            long tenantId,
            UUID populationPublicId,
            long populationRevision,
            String populationDigest,
            String jurisdiction,
            long policyRevision) {
        List<PolicyCandidate> global = jdbc.query("""
                SELECT wr.tenant_id,
                       wr.public_id,
                       wr.revision,
                       wr.arrangement_kind,
                       wr.scope_type,
                       wr.scope_public_ref,
                       wr.precedence_priority,
                       wr.effective_from,
                       wr.effective_to,
                       wr.rule_pack_public_id,
                       rp.jurisdiction_country,
                       wr.policy_revision,
                       wr.lifecycle_state,
                       wr.author_actor_id,
                       wr.template_digest
                  FROM tim_work_regime_versions wr
                  JOIN tim_rule_pack_versions rp
                    ON rp.tenant_id = wr.tenant_id
                   AND rp.public_id = wr.rule_pack_public_id
                   AND rp.policy_revision = wr.policy_revision
                 WHERE wr.tenant_id = ?
                   AND wr.scope_type = 'GLOBAL'
                   AND rp.jurisdiction_country = ?
                   AND wr.policy_revision = ?
                 ORDER BY wr.public_id
                 FOR SHARE OF wr
                """, JdbcWorkRegimeReadSupport::mapPolicyCandidate,
                tenantId, jurisdiction, policyRevision);
        List<PolicyCandidate> population = jdbc.query("""
                SELECT wr.tenant_id,
                       wr.public_id,
                       wr.revision,
                       wr.arrangement_kind,
                       wr.scope_type,
                       wr.scope_public_ref,
                       wr.precedence_priority,
                       wr.effective_from,
                       wr.effective_to,
                       wr.rule_pack_public_id,
                       rp.jurisdiction_country,
                       wr.policy_revision,
                       wr.lifecycle_state,
                       wr.author_actor_id,
                       wr.template_digest
                  FROM tim_work_regime_versions wr
                  JOIN tim_rule_pack_versions rp
                    ON rp.tenant_id = wr.tenant_id
                   AND rp.public_id = wr.rule_pack_public_id
                   AND rp.policy_revision = wr.policy_revision
                  JOIN tim_work_plan_assignments a
                    ON a.tenant_id = wr.tenant_id
                   AND a.work_regime_version_id = wr.work_regime_version_id
                   AND a.policy_revision = wr.policy_revision
                  JOIN tim_work_plan_target_evidence te
                    ON te.tenant_id = a.tenant_id
                   AND te.work_plan_assignment_id = a.work_plan_assignment_id
                  JOIN tim_target_population_projections p
                    ON p.tenant_id = te.tenant_id
                   AND p.population_public_id = te.population_public_id
                   AND p.projection_revision = te.population_revision
                   AND p.source_digest = te.population_digest
                  JOIN tim_target_population_members m
                    ON m.tenant_id = a.tenant_id
                   AND m.population_public_id = p.population_public_id
                   AND m.population_revision = p.projection_revision
                   AND m.worker_public_id = a.worker_public_id
                   AND m.people_assignment_public_id = a.people_assignment_public_id
                   AND m.people_assignment_revision = a.people_assignment_revision
                   AND m.membership_revision = te.membership_revision
                   AND m.source_digest = te.membership_digest
                 WHERE wr.tenant_id = ?
                   AND wr.scope_type = 'POPULATION'
                   AND wr.scope_public_ref = p.scope_public_ref
                   AND wr.author_actor_id = te.author_actor_id
                   AND p.population_public_id = ?
                   AND p.projection_revision = ?
                   AND p.source_digest = ?
                   AND p.lifecycle_state = 'ACTIVE'
                   AND p.effective_from <= (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date
                   AND (p.effective_to IS NULL
                        OR p.effective_to > (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date)
                   AND p.effective_from <= a.effective_from
                   AND (p.effective_to IS NULL
                        OR (a.effective_to IS NOT NULL AND p.effective_to >= a.effective_to))
                   AND m.lifecycle_state = 'ACTIVE'
                   AND m.effective_from <= a.effective_from
                   AND (m.effective_to IS NULL
                        OR (a.effective_to IS NOT NULL AND m.effective_to >= a.effective_to))
                   AND rp.jurisdiction_country = ?
                   AND wr.policy_revision = ?
                 ORDER BY wr.public_id
                 FOR SHARE OF wr, a, p, m
                """, JdbcWorkRegimeReadSupport::mapPolicyCandidate,
                tenantId, populationPublicId, populationRevision, populationDigest,
                jurisdiction, policyRevision);
        return java.util.stream.Stream.concat(global.stream(), population.stream())
                .distinct()
                .sorted(java.util.Comparator.comparing(candidate -> candidate.publicId().toString()))
                .toList();
    }

    List<AssignmentPlan> findSimulationAssignments(
            long tenantId, UUID workRegimePublicId, EffectivePeriod requestedPeriod) {
        return findSimulationAssignments(
                tenantId, workRegimePublicId, null, requestedPeriod);
    }

    List<AssignmentPlan> findSimulationAssignments(
            long tenantId,
            UUID workRegimePublicId,
            UUID populationPublicId,
            EffectivePeriod requestedPeriod) {
        List<AssignmentRow> targets = jdbc.query("""
                SELECT a.work_plan_assignment_id,
                       a.public_id,
                       a.worker_public_id,
                       a.people_assignment_public_id,
                       a.people_assignment_revision,
                       a.effective_from,
                       a.effective_to,
                       a.zone_id,
                       wr.work_regime_version_id,
                       wr.public_id AS regime_public_id,
                       wr.revision AS regime_revision
                  FROM tim_work_plan_assignments a
                  JOIN tim_work_regime_versions wr
                    ON wr.tenant_id = a.tenant_id
                   AND wr.work_regime_version_id = a.work_regime_version_id
                   AND wr.policy_revision = a.policy_revision
                  JOIN tim_work_plan_target_evidence target_evidence
                    ON target_evidence.tenant_id = a.tenant_id
                   AND target_evidence.work_plan_assignment_id = a.work_plan_assignment_id
                  JOIN tim_target_population_projections population
                    ON population.tenant_id = target_evidence.tenant_id
                   AND population.population_public_id = target_evidence.population_public_id
                   AND population.projection_revision = target_evidence.population_revision
                   AND population.source_digest = target_evidence.population_digest
                   AND population.lifecycle_state = 'ACTIVE'
                  JOIN tim_target_population_members member
                    ON member.tenant_id = a.tenant_id
                   AND member.population_public_id = population.population_public_id
                   AND member.population_revision = population.projection_revision
                   AND member.worker_public_id = a.worker_public_id
                   AND member.people_assignment_public_id = a.people_assignment_public_id
                   AND member.people_assignment_revision = a.people_assignment_revision
                   AND member.membership_revision = target_evidence.membership_revision
                   AND member.source_digest = target_evidence.membership_digest
                   AND member.lifecycle_state = 'ACTIVE'
                 WHERE a.tenant_id = ?
                   AND wr.public_id = ?
                   AND (?::uuid IS NULL OR target_evidence.population_public_id = ?)
                   AND a.lifecycle_state IN ('DRAFT', 'PUBLISHED')
                   AND wr.lifecycle_state IN (
                       'DRAFT', 'VALIDATED', 'SIMULATED',
                       'IN_REVIEW', 'APPROVED', 'PUBLISHED'
                   )
                   AND a.effective_from <= ?
                   AND (a.effective_to IS NULL OR a.effective_to >= ?)
                   AND wr.effective_from <= ?
                   AND (wr.effective_to IS NULL OR wr.effective_to >= ?)
                   AND member.effective_from <= ?
                   AND (member.effective_to IS NULL
                        OR (? IS NOT NULL AND member.effective_to >= ?))
                 ORDER BY a.public_id
                """, JdbcWorkRegimeReadSupport::mapAssignment,
                tenantId,
                workRegimePublicId,
                populationPublicId,
                populationPublicId,
                requestedPeriod.from(),
                requestedPeriod.to(),
                requestedPeriod.from(),
                requestedPeriod.to(),
                requestedPeriod.from(),
                requestedPeriod.to(),
                requestedPeriod.to());

        List<AssignmentPlan> plans = new ArrayList<>();
        Set<UUID> targetAssignmentIds = new HashSet<>();
        Set<WorkerAssignmentIdentity> targetIdentities = new HashSet<>();
        targets.forEach(target -> targetAssignmentIds.add(target.assignmentPublicId()));
        targets.forEach(target -> targetIdentities.add(new WorkerAssignmentIdentity(
                target.workerPublicId(), target.peopleAssignmentPublicId())));
        Set<UUID> addedContextAssignments = new HashSet<>();
        for (AssignmentRow target : targets) {
            ScheduleTemplate draft = template(
                    tenantId,
                    target.workRegimeVersionId(),
                    target.regimePublicId(),
                    target.regimeRevision());
            ScheduleTemplate empty = new ScheduleTemplate(
                    "UNASSIGNED:" + target.assignmentPublicId(), 1, List.of());
            plans.add(new AssignmentPlan(
                    tenantId,
                    target.assignmentPublicId(),
                    target.workerPublicId(),
                    target.peopleAssignmentPublicId(),
                    target.peopleAssignmentRevision(),
                    target.period(),
                    target.zoneId(),
                    empty,
                    draft));
            for (AssignmentRow existing : currentAssignments(
                    tenantId, populationPublicId,
                    target.peopleAssignmentPublicId(), requestedPeriod)) {
                requireSameWorker(
                        target.workerPublicId(), existing.workerPublicId(),
                        target.assignmentPublicId());
                if (!existing.zoneId().equals(target.zoneId())) {
                    throw new IllegalStateException(
                            "Current and draft assignment zones differ for "
                                    + target.assignmentPublicId());
                }
                ScheduleTemplate current = template(
                        tenantId,
                        existing.workRegimeVersionId(),
                        existing.regimePublicId(),
                        existing.regimeRevision());
                plans.add(new AssignmentPlan(
                        tenantId,
                        target.assignmentPublicId(),
                        target.workerPublicId(),
                        target.peopleAssignmentPublicId(),
                        existing.peopleAssignmentRevision(),
                        existing.period(),
                        target.zoneId(),
                        current,
                        empty));
            }
            for (AssignmentRow other : otherEffectiveAssignments(
                    tenantId, populationPublicId, target, requestedPeriod)) {
                if (targetAssignmentIds.contains(other.assignmentPublicId())
                        || targetIdentities.contains(new WorkerAssignmentIdentity(
                                other.workerPublicId(), other.peopleAssignmentPublicId()))
                        || !addedContextAssignments.add(other.assignmentPublicId())) {
                    continue;
                }
                ScheduleTemplate published = template(
                        tenantId,
                        other.workRegimeVersionId(),
                        other.regimePublicId(),
                        other.regimeRevision());
                plans.add(new AssignmentPlan(
                        tenantId,
                        other.assignmentPublicId(),
                        other.workerPublicId(),
                        other.peopleAssignmentPublicId(),
                        other.peopleAssignmentRevision(),
                        other.period(),
                        other.zoneId(),
                        published,
                        published));
            }
        }
        return List.copyOf(plans);
    }

    static void requireSameWorker(UUID targetWorkerId, UUID currentWorkerId, UUID assignmentId) {
        if (!currentWorkerId.equals(targetWorkerId)) {
            throw new IllegalStateException(
                    "Current and draft assignments belong to different workers for "
                            + assignmentId);
        }
    }

    WorkRegimeRevision loadRevision(long tenantId, UUID publicId) {
        return exactlyOne(jdbc.query("""
                SELECT tenant_id, public_id, revision, version, lifecycle_state,
                       author_actor_id, approval_actor_id, scope_public_ref,
                       effective_from, effective_to, template_digest
                  FROM tim_work_regime_versions
                 WHERE tenant_id = ?
                   AND public_id = ?
                """, JdbcWorkRegimeReadSupport::mapRevision, tenantId, publicId),
                "work-regime revision", publicId);
    }

    private List<WorkPlanRecord> findPlanRows(
            long tenantId, UUID publicId, UUID populationPublicId) {
        if (populationPublicId == null) {
            return hydrate(jdbc.query(
                    PLAN_COLUMNS
                            + " WHERE wr.tenant_id = ? AND wr.public_id = ? ORDER BY a.public_id",
                    JdbcWorkRegimeReadSupport::mapPlanRow, tenantId, publicId));
        }
        return hydrate(jdbc.query(
                PLAN_COLUMNS + currentTargetJoins() + """
                     WHERE wr.tenant_id = ?
                       AND wr.public_id = ?
                       AND te.population_public_id = ?
                       AND wr.scope_type = 'POPULATION'
                       AND wr.scope_public_ref = p.scope_public_ref
                       AND wr.author_actor_id = te.author_actor_id
                     ORDER BY a.public_id
                     FOR SHARE OF p, m
                    """, JdbcWorkRegimeReadSupport::mapPlanRow,
                tenantId, publicId, populationPublicId));
    }

    private static String currentTargetJoins() {
        return """
              JOIN tim_target_population_projections p
                ON p.tenant_id = te.tenant_id
               AND p.population_public_id = te.population_public_id
               AND p.projection_revision = te.population_revision
               AND p.source_digest = te.population_digest
               AND p.lifecycle_state = 'ACTIVE'
               AND p.effective_from <= a.effective_from
               AND (p.effective_to IS NULL
                    OR (a.effective_to IS NOT NULL AND p.effective_to >= a.effective_to))
              JOIN tim_target_population_members m
                ON m.tenant_id = a.tenant_id
               AND m.population_public_id = p.population_public_id
               AND m.population_revision = p.projection_revision
               AND m.worker_public_id = a.worker_public_id
               AND m.people_assignment_public_id = a.people_assignment_public_id
               AND m.people_assignment_revision = a.people_assignment_revision
               AND m.membership_revision = te.membership_revision
               AND m.source_digest = te.membership_digest
               AND m.lifecycle_state = 'ACTIVE'
               AND m.effective_from <= a.effective_from
               AND (m.effective_to IS NULL
                    OR (a.effective_to IS NOT NULL AND m.effective_to >= a.effective_to))
            """;
    }

    private List<WorkPlanRecord> hydrate(List<PlanRow> rows) {
        List<WorkPlanRecord> plans = new ArrayList<>(rows.size());
        for (PlanRow row : rows) {
            plans.add(row.toRecord(loadSegments(row.tenantId(), row.internalId())));
        }
        return List.copyOf(plans);
    }

    private List<LocalSegment> loadSegments(long tenantId, long workRegimeVersionId) {
        return jdbc.query(SEGMENTS_SQL, (row, number) -> new LocalSegment(
                row.getString("segment_key"),
                DayOfWeek.of(row.getInt("iso_day_of_week")),
                SegmentKind.valueOf(row.getString("segment_kind")),
                row.getObject("local_start_time", LocalTime.class),
                row.getObject("local_end_time", LocalTime.class),
                row.getInt("end_day_offset"),
                DstOverlapPolicy.valueOf(row.getString("dst_overlap_policy"))),
                tenantId,
                workRegimeVersionId);
    }

    private ScheduleTemplate template(
            long tenantId, long internalId, UUID publicId, long revision) {
        return new ScheduleTemplate(
                publicId.toString(), revision, loadSegments(tenantId, internalId));
    }

    private List<AssignmentRow> currentAssignments(
            long tenantId,
            UUID populationPublicId,
            UUID peopleAssignmentPublicId,
            EffectivePeriod period) {
        return jdbc.query("""
                SELECT a.work_plan_assignment_id,
                       a.public_id,
                       a.worker_public_id,
                       a.people_assignment_public_id,
                       a.people_assignment_revision,
                       GREATEST(a.effective_from, wr.effective_from) AS effective_from,
                       CASE
                           WHEN a.effective_to IS NULL THEN wr.effective_to
                           WHEN wr.effective_to IS NULL THEN a.effective_to
                           ELSE LEAST(a.effective_to, wr.effective_to)
                       END AS effective_to,
                       a.zone_id,
                       wr.work_regime_version_id,
                       wr.public_id AS regime_public_id,
                       wr.revision AS regime_revision
                  FROM tim_work_plan_assignments a
                  JOIN tim_work_regime_versions wr
                    ON wr.tenant_id = a.tenant_id
                   AND wr.work_regime_version_id = a.work_regime_version_id
                   AND wr.policy_revision = a.policy_revision
                  JOIN tim_work_plan_target_evidence target_evidence
                    ON target_evidence.tenant_id = a.tenant_id
                   AND target_evidence.work_plan_assignment_id = a.work_plan_assignment_id
                  JOIN tim_target_population_projections population
                    ON population.tenant_id = target_evidence.tenant_id
                   AND population.population_public_id = target_evidence.population_public_id
                   AND population.projection_revision = target_evidence.population_revision
                   AND population.source_digest = target_evidence.population_digest
                   AND population.lifecycle_state = 'ACTIVE'
                  JOIN tim_target_population_members member
                    ON member.tenant_id = a.tenant_id
                   AND member.population_public_id = population.population_public_id
                   AND member.population_revision = population.projection_revision
                   AND member.worker_public_id = a.worker_public_id
                   AND member.people_assignment_public_id = a.people_assignment_public_id
                   AND member.people_assignment_revision = a.people_assignment_revision
                   AND member.membership_revision = target_evidence.membership_revision
                   AND member.source_digest = target_evidence.membership_digest
                   AND member.lifecycle_state = 'ACTIVE'
                 WHERE a.tenant_id = ?
                   AND (?::uuid IS NULL OR target_evidence.population_public_id = ?)
                   AND a.people_assignment_public_id = ?
                   AND a.lifecycle_state = 'PUBLISHED'
                   AND wr.lifecycle_state = 'PUBLISHED'
                   AND a.effective_from < ?
                   AND (a.effective_to IS NULL OR a.effective_to > ?)
                   AND wr.effective_from < ?
                   AND (wr.effective_to IS NULL OR wr.effective_to > ?)
                   AND daterange(a.effective_from, a.effective_to, '[)')
                       && daterange(wr.effective_from, wr.effective_to, '[)')
                   AND member.effective_from <= ?
                   AND (member.effective_to IS NULL OR member.effective_to >= ?)
                 ORDER BY a.effective_from, wr.revision, a.public_id
                """, JdbcWorkRegimeReadSupport::mapAssignment,
                tenantId,
                populationPublicId,
                populationPublicId,
                peopleAssignmentPublicId,
                period.to(),
                period.from(),
                period.to(),
                period.from(),
                period.from(),
                period.to());
    }

    private List<AssignmentRow> otherEffectiveAssignments(
            long tenantId,
            UUID populationPublicId,
            AssignmentRow target,
            EffectivePeriod period) {
        return jdbc.query("""
                SELECT a.work_plan_assignment_id,
                       a.public_id,
                       a.worker_public_id,
                       a.people_assignment_public_id,
                       a.people_assignment_revision,
                       GREATEST(a.effective_from, wr.effective_from) AS effective_from,
                       CASE
                           WHEN a.effective_to IS NULL THEN wr.effective_to
                           WHEN wr.effective_to IS NULL THEN a.effective_to
                           ELSE LEAST(a.effective_to, wr.effective_to)
                       END AS effective_to,
                       a.zone_id,
                       wr.work_regime_version_id,
                       wr.public_id AS regime_public_id,
                       wr.revision AS regime_revision
                  FROM tim_work_plan_assignments a
                  JOIN tim_work_regime_versions wr
                    ON wr.tenant_id = a.tenant_id
                   AND wr.work_regime_version_id = a.work_regime_version_id
                   AND wr.policy_revision = a.policy_revision
                  JOIN tim_work_plan_target_evidence target_evidence
                    ON target_evidence.tenant_id = a.tenant_id
                   AND target_evidence.work_plan_assignment_id = a.work_plan_assignment_id
                  JOIN tim_target_population_projections population
                    ON population.tenant_id = target_evidence.tenant_id
                   AND population.population_public_id = target_evidence.population_public_id
                   AND population.projection_revision = target_evidence.population_revision
                   AND population.source_digest = target_evidence.population_digest
                   AND population.lifecycle_state = 'ACTIVE'
                  JOIN tim_target_population_members member
                    ON member.tenant_id = a.tenant_id
                   AND member.population_public_id = population.population_public_id
                   AND member.population_revision = population.projection_revision
                   AND member.worker_public_id = a.worker_public_id
                   AND member.people_assignment_public_id = a.people_assignment_public_id
                   AND member.people_assignment_revision = a.people_assignment_revision
                   AND member.membership_revision = target_evidence.membership_revision
                   AND member.source_digest = target_evidence.membership_digest
                   AND member.lifecycle_state = 'ACTIVE'
                 WHERE a.tenant_id = ?
                   AND (?::uuid IS NULL OR target_evidence.population_public_id = ?)
                   AND a.worker_public_id = ?
                   AND a.people_assignment_public_id <> ?
                   AND a.lifecycle_state = 'PUBLISHED'
                   AND wr.lifecycle_state = 'PUBLISHED'
                   AND a.effective_from < ?
                   AND (a.effective_to IS NULL OR a.effective_to > ?)
                   AND wr.effective_from < ?
                   AND (wr.effective_to IS NULL OR wr.effective_to > ?)
                   AND daterange(a.effective_from, a.effective_to, '[)')
                       && daterange(wr.effective_from, wr.effective_to, '[)')
                   AND member.effective_from <= ?
                   AND (member.effective_to IS NULL
                        OR (? IS NOT NULL AND member.effective_to >= ?))
                 ORDER BY a.people_assignment_public_id, a.public_id
                """, JdbcWorkRegimeReadSupport::mapAssignment,
                tenantId,
                populationPublicId,
                populationPublicId,
                target.workerPublicId(),
                target.peopleAssignmentPublicId(),
                period.to(),
                period.from(),
                period.to(),
                period.from(),
                period.from(),
                period.to(),
                period.to());
    }

    private static PlanRow mapPlanRow(ResultSet row, int number) throws SQLException {
        return new PlanRow(
                row.getLong("work_regime_version_id"),
                row.getLong("tenant_id"),
                uuid(row, "regime_public_id"),
                row.getLong("regime_revision"),
                row.getLong("regime_version"),
                PolicyState.valueOf(row.getString("regime_state")),
                row.getLong("author_actor_id"),
                nullableLong(row, "approval_actor_id"),
                row.getString("scope_public_ref"),
                period(row, "regime_from", "regime_to"),
                row.getString("template_digest").trim(),
                row.getString("display_name"),
                ArrangementKind.valueOf(row.getString("arrangement_kind")),
                row.getString("extension_schema_ref"),
                WorkRegimeModels.ScopeType.valueOf(row.getString("scope_type")),
                uuid(row, "rule_pack_public_id"),
                row.getString("jurisdiction_country").trim(),
                row.getLong("policy_revision"),
                row.getString("resolution_digest").trim(),
                uuid(row, "assignment_public_id"),
                uuid(row, "worker_public_id"),
                uuid(row, "people_assignment_public_id"),
                row.getLong("people_assignment_revision"),
                period(row, "assignment_from", "assignment_to"),
                row.getString("zone_id"),
                new TargetBindingEvidence(
                        uuid(row, "target_population_public_id"),
                        row.getLong("target_population_revision"),
                        uuid(row, "worker_public_id"),
                        uuid(row, "people_assignment_public_id"),
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

    private static AssignmentRow mapAssignment(ResultSet row, int number) throws SQLException {
        return new AssignmentRow(
                row.getLong("work_plan_assignment_id"),
                uuid(row, "public_id"),
                uuid(row, "worker_public_id"),
                uuid(row, "people_assignment_public_id"),
                row.getLong("people_assignment_revision"),
                period(row, "effective_from", "effective_to"),
                row.getString("zone_id"),
                row.getLong("work_regime_version_id"),
                uuid(row, "regime_public_id"),
                row.getLong("regime_revision"));
    }

    private static WorkRegimeRevision mapRevision(ResultSet row, int number) throws SQLException {
        return new WorkRegimeRevision(
                row.getLong("tenant_id"),
                uuid(row, "public_id"),
                row.getLong("revision"),
                row.getLong("version"),
                PolicyState.valueOf(row.getString("lifecycle_state")),
                row.getLong("author_actor_id"),
                nullableLong(row, "approval_actor_id"),
                row.getString("scope_public_ref"),
                period(row, "effective_from", "effective_to"),
                row.getString("template_digest").trim());
    }

    private static PolicyCandidate mapPolicyCandidate(ResultSet row, int number)
            throws SQLException {
        return new PolicyCandidate(
                row.getLong("tenant_id"),
                uuid(row, "public_id"),
                row.getLong("revision"),
                ArrangementKind.valueOf(row.getString("arrangement_kind")),
                WorkRegimeModels.ScopeType.valueOf(row.getString("scope_type")),
                row.getString("scope_public_ref"),
                row.getInt("precedence_priority"),
                period(row, "effective_from", "effective_to"),
                uuid(row, "rule_pack_public_id"),
                row.getString("jurisdiction_country").trim(),
                row.getLong("policy_revision"),
                PolicyState.valueOf(row.getString("lifecycle_state")),
                row.getLong("author_actor_id"),
                row.getString("template_digest").trim());
    }

    private static EffectivePeriod period(ResultSet row, String from, String to)
            throws SQLException {
        return new EffectivePeriod(
                row.getObject(from, LocalDate.class), row.getObject(to, LocalDate.class));
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static <T> T exactlyOne(List<T> rows, String label, Object publicId) {
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one " + label + " for " + publicId + "; found " + rows.size());
        }
        return rows.getFirst();
    }

    private record PlanRow(
            long internalId,
            long tenantId,
            UUID publicId,
            long revision,
            long version,
            PolicyState state,
            long authorActorId,
            Long approvalActorId,
            String scopeRef,
            EffectivePeriod regimePeriod,
            String artifactDigest,
            String displayName,
            ArrangementKind arrangementKind,
            String extensionCode,
            WorkRegimeModels.ScopeType scopeType,
            UUID rulePackPublicId,
            String jurisdiction,
            long policyRevision,
            String resolutionDigest,
            UUID assignmentPublicId,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long peopleAssignmentRevision,
            EffectivePeriod assignmentPeriod,
            String zoneId,
            TargetBindingEvidence targetBindingEvidence) {

        WorkPlanRecord toRecord(List<LocalSegment> segments) {
            return new WorkPlanRecord(
                    new WorkRegimeRevision(
                            tenantId, publicId, revision, version, state, authorActorId,
                            approvalActorId, scopeRef, regimePeriod, artifactDigest),
                    displayName,
                    arrangementKind,
                    extensionCode,
                    scopeType,
                    rulePackPublicId,
                    jurisdiction,
                    policyRevision,
                    resolutionDigest,
                    zoneId,
                    assignmentPublicId,
                    workerPublicId,
                    peopleAssignmentPublicId,
                    peopleAssignmentRevision,
                    assignmentPeriod,
                    targetBindingEvidence,
                    segments);
        }
    }

    private record AssignmentRow(
            long internalId,
            UUID assignmentPublicId,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long peopleAssignmentRevision,
            EffectivePeriod period,
            String zoneId,
            long workRegimeVersionId,
            UUID regimePublicId,
            long regimeRevision) {
    }

    private record WorkerAssignmentIdentity(UUID workerPublicId, UUID peopleAssignmentPublicId) {
    }
}
