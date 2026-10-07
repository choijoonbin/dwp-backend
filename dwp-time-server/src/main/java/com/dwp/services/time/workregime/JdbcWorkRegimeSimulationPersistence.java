package com.dwp.services.time.workregime;

import com.dwp.services.time.workregime.JdbcWorkRegimeSimulationAssignmentSupport.SimulationAssignment;
import com.dwp.services.time.workregime.WorkRegimeModels.DiffKind;
import com.dwp.services.time.workregime.WorkRegimeModels.DstResolution;
import com.dwp.services.time.workregime.WorkRegimeModels.ResolvedSegment;
import com.dwp.services.time.workregime.WorkRegimeModels.ScheduleDiff;
import com.dwp.services.time.workregime.WorkRegimeModels.SegmentKind;
import com.dwp.services.time.workregime.WorkRegimeModels.SimulationResult;
import com.dwp.services.time.workregime.WorkRegimeModels.SimulationState;
import com.dwp.services.time.workregime.WorkRegimeRepository.SimulationWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.StoredSimulation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Simulation-specific SQL kept separate from the owner repository's plan persistence. */
final class JdbcWorkRegimeSimulationPersistence {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final JdbcWorkRegimeSimulationAssignmentSupport assignmentSupport;

    JdbcWorkRegimeSimulationPersistence(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.assignmentSupport = new JdbcWorkRegimeSimulationAssignmentSupport(jdbc);
    }

    void save(SimulationWrite simulation) {
        SimulationRegime regime = exactlyOne(jdbc.query("""
                SELECT work_regime_version_id,
                       policy_revision,
                       rule_pack_public_id,
                       resolution_digest,
                       default_zone_id
                  FROM tim_work_regime_versions
                 WHERE tenant_id = ?
                   AND public_id = ?
                """, JdbcWorkRegimeSimulationPersistence::mapRegime,
                simulation.tenantId(),
                simulation.workRegimePublicId()),
                "simulation work regime", simulation.workRegimePublicId());
        verifyIdentity(simulation, regime);
        verifyReceipt(simulation);

        Map<UUID, SimulationAssignment> assignments = assignmentSupport.load(
                simulation, regime.internalId());
        Long singleAssignmentId = assignments.size() == 1
                && assignments.values().iterator().next().workRegimeVersionId()
                        == regime.internalId()
                ? assignments.values().iterator().next().internalId()
                : null;
        Long runIdValue = jdbc.queryForObject("""
                INSERT INTO tim_schedule_simulation_runs (
                    public_id, tenant_id, work_regime_version_id,
                    work_plan_assignment_id, simulation_from, simulation_to,
                    lifecycle_state, command_receipt_public_id, idempotency_key,
                    request_digest, zone_id, tzdb_version, rule_pack_public_id,
                    policy_revision, resolution_digest, created_by
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, 'ACCEPTED', ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                RETURNING schedule_simulation_run_id
                """, Long.class,
                simulation.publicId(),
                simulation.tenantId(),
                regime.internalId(),
                singleAssignmentId,
                simulation.period().from(),
                simulation.period().to(),
                simulation.receiptId(),
                simulation.idempotencyKey(),
                simulation.requestDigest(),
                simulation.zoneId(),
                simulation.tzdbVersion(),
                simulation.rulePackPublicId(),
                simulation.policyRevision(),
                simulation.resolutionDigest(),
                simulation.actorId());
        long runId = requireInternalId(runIdValue, "simulation run");
        int started = jdbc.update("""
                UPDATE tim_schedule_simulation_runs
                   SET lifecycle_state = 'RUNNING'
                 WHERE tenant_id = ?
                   AND schedule_simulation_run_id = ?
                   AND lifecycle_state = 'ACCEPTED'
                """, simulation.tenantId(), runId);
        if (started != 1) {
            throw new IllegalStateException("Simulation did not enter RUNNING state");
        }

        insertRows(simulation, runId, assignments);
        String terminalState = simulation.result().state() == SimulationState.SUCCEEDED
                ? "SUCCEEDED" : "REJECTED";
        int terminal = jdbc.update("""
                UPDATE tim_schedule_simulation_runs
                   SET lifecycle_state = ?,
                       result_digest = ?,
                       calculated_at = ?,
                       findings = CAST(? AS jsonb),
                       finding_count = ?,
                       partial_failure_count = 0
                 WHERE tenant_id = ?
                   AND schedule_simulation_run_id = ?
                   AND lifecycle_state = 'RUNNING'
                """,
                terminalState,
                simulation.resultDigest(),
                Timestamp.from(simulation.calculatedAt()),
                json(simulation.result().findings()),
                simulation.result().findings().size(),
                simulation.tenantId(),
                runId);
        if (terminal != 1) {
            throw new IllegalStateException("Simulation did not reach its terminal state");
        }
    }

    Optional<StoredSimulation> findByReceipt(long tenantId, UUID receiptId) {
        List<SimulationRow> rows = jdbc.query("""
                SELECT run.schedule_simulation_run_id,
                       run.lifecycle_state,
                       run.result_digest,
                       run.calculated_at,
                       run.tzdb_version,
                       run.policy_revision,
                       run.findings::text AS findings_json,
                       run.finding_count,
                       wr.public_id AS work_regime_public_id,
                       receipt.expected_version
                  FROM tim_schedule_simulation_runs run
                  JOIN tim_work_regime_versions wr
                    ON wr.tenant_id = run.tenant_id
                   AND wr.work_regime_version_id = run.work_regime_version_id
                  JOIN tim_command_receipts receipt
                    ON receipt.tenant_id = run.tenant_id
                   AND receipt.public_id = run.command_receipt_public_id
                   AND receipt.operation = 'SIMULATE'
                 WHERE run.tenant_id = ?
                   AND run.command_receipt_public_id = ?
                   AND run.lifecycle_state IN ('SUCCEEDED', 'REJECTED')
                """, JdbcWorkRegimeSimulationPersistence::mapSimulationRow,
                tenantId, receiptId);
        if (rows.isEmpty()) return Optional.empty();
        SimulationRow run = exactlyOne(rows, "simulation receipt", receiptId);
        SimulationResult result = loadResult(tenantId, run);
        return Optional.of(new StoredSimulation(
                receiptId,
                run.workRegimePublicId(),
                run.baseVersion(),
                result,
                run.resultDigest()));
    }

    private void verifyReceipt(SimulationWrite simulation) {
        Integer evidence = jdbc.queryForObject("""
                SELECT count(*)
                  FROM tim_command_receipts
                 WHERE tenant_id = ?
                   AND public_id = ?
                   AND idempotency_key = ?
                   AND operation = 'SIMULATE'
                   AND aggregate_public_id = ?
                   AND actor_id = ?
                   AND lifecycle_state IN ('RUNNING', 'SUCCEEDED')
                """, Integer.class,
                simulation.tenantId(),
                simulation.receiptId(),
                simulation.idempotencyKey(),
                simulation.workRegimePublicId(),
                simulation.actorId());
        if (evidence == null || evidence != 1) {
            throw new IllegalStateException(
                    "Simulation requires its matching in-flight command receipt");
        }
    }

    private void insertRows(
            SimulationWrite simulation,
            long runId,
            Map<UUID, SimulationAssignment> assignments) {
        Map<SimulationKey, ResolvedSegment> segments = uniqueSegments(
                simulation.result().segments());
        Map<SimulationKey, ScheduleDiff> differences = uniqueDifferences(
                simulation.result().differences());
        Set<SimulationKey> keys = new TreeSet<>();
        keys.addAll(segments.keySet());
        keys.addAll(differences.keySet());
        for (SimulationKey key : keys) {
            ResolvedSegment segment = segments.get(key);
            ScheduleDiff difference = differences.get(key);
            SimulationAssignment assignment = Objects.requireNonNull(
                    assignments.get(key.assignmentId()), "simulation assignment is missing");
            SegmentEvidence evidence = segment == null
                    ? removedEvidence(simulation, assignment, key)
                    : SegmentEvidence.from(segment);
            DiffKind change = difference == null ? DiffKind.UNCHANGED : difference.kind();
            Long currentMinutes = difference == null
                    ? Long.valueOf(segment.minutes())
                    : difference.currentMinutes();
            Long draftMinutes = difference == null
                    ? Long.valueOf(segment.minutes())
                    : difference.draftMinutes();
            jdbc.update("""
                    INSERT INTO tim_schedule_simulation_segments (
                        tenant_id, schedule_simulation_run_id, work_plan_assignment_id,
                        segment_key, change_kind, segment_kind, local_work_date,
                        start_at, end_at, zone_id, start_offset_seconds,
                        end_offset_seconds, overnight, dst_resolution,
                        current_minutes, draft_minutes, rule_reference
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    simulation.tenantId(),
                    runId,
                    assignment.internalId(),
                    key.segmentKey(),
                    change.name(),
                    evidence.kind().name(),
                    key.workDate(),
                    evidence.startAt() == null ? null : Timestamp.from(evidence.startAt()),
                    evidence.endAt() == null ? null : Timestamp.from(evidence.endAt()),
                    evidence.zoneId(),
                    evidence.startOffsetSeconds(),
                    evidence.endOffsetSeconds(),
                    evidence.overnight(),
                    evidence.dstResolution().name(),
                    minutes(currentMinutes),
                    minutes(draftMinutes),
                    ruleReference(simulation));
        }
    }

    private SegmentEvidence removedEvidence(
            SimulationWrite simulation,
            SimulationAssignment assignment,
            SimulationKey key) {
        List<SegmentDefinition> definitions = jdbc.query("""
                SELECT s.segment_key,
                       s.segment_kind,
                       s.end_day_offset,
                       current_assignment.zone_id
                  FROM tim_work_plan_assignments target_assignment
                  JOIN tim_work_plan_target_evidence target_evidence
                    ON target_evidence.tenant_id = target_assignment.tenant_id
                   AND target_evidence.work_plan_assignment_id =
                       target_assignment.work_plan_assignment_id
                  JOIN tim_work_plan_assignments current_assignment
                    ON current_assignment.tenant_id = target_assignment.tenant_id
                   AND current_assignment.people_assignment_public_id =
                       target_assignment.people_assignment_public_id
                   AND current_assignment.lifecycle_state = 'PUBLISHED'
                   AND current_assignment.effective_from <= ?
                   AND (current_assignment.effective_to IS NULL
                        OR current_assignment.effective_to > ?)
                  JOIN tim_work_regime_versions current_regime
                    ON current_regime.tenant_id = current_assignment.tenant_id
                   AND current_regime.work_regime_version_id =
                       current_assignment.work_regime_version_id
                   AND current_regime.policy_revision = current_assignment.policy_revision
                   AND current_regime.lifecycle_state = 'PUBLISHED'
                  JOIN tim_work_plan_target_evidence current_evidence
                    ON current_evidence.tenant_id = current_assignment.tenant_id
                   AND current_evidence.work_plan_assignment_id =
                       current_assignment.work_plan_assignment_id
                   AND current_evidence.population_public_id =
                       target_evidence.population_public_id
                  JOIN tim_target_population_projections population
                    ON population.tenant_id = current_evidence.tenant_id
                   AND population.population_public_id = current_evidence.population_public_id
                   AND population.projection_revision = current_evidence.population_revision
                   AND population.source_digest = current_evidence.population_digest
                   AND population.lifecycle_state = 'ACTIVE'
                  JOIN tim_target_population_members member
                    ON member.tenant_id = current_assignment.tenant_id
                   AND member.population_public_id = population.population_public_id
                   AND member.population_revision = population.projection_revision
                   AND member.worker_public_id = current_assignment.worker_public_id
                   AND member.people_assignment_public_id =
                       current_assignment.people_assignment_public_id
                   AND member.people_assignment_revision =
                       current_assignment.people_assignment_revision
                   AND member.membership_revision = current_evidence.membership_revision
                   AND member.source_digest = current_evidence.membership_digest
                   AND member.lifecycle_state = 'ACTIVE'
                  JOIN tim_work_regime_segments s
                    ON s.tenant_id = current_assignment.tenant_id
                   AND s.work_regime_version_id = current_assignment.work_regime_version_id
                 WHERE target_assignment.tenant_id = ?
                   AND target_assignment.public_id = ?
                   AND target_evidence.population_public_id = ?
                   AND s.iso_day_of_week = ?
                   AND s.segment_key = ?
                   AND current_regime.effective_from <= ?
                   AND (current_regime.effective_to IS NULL
                        OR current_regime.effective_to > ?)
                   AND member.effective_from <= ?
                   AND (member.effective_to IS NULL OR member.effective_to > ?)
                 FOR SHARE OF current_assignment, current_regime, population, member
                """, (row, number) -> new SegmentDefinition(
                    row.getString("segment_key"),
                    SegmentKind.valueOf(row.getString("segment_kind")),
                    row.getInt("end_day_offset") == 1,
                    row.getString("zone_id")),
                key.workDate(),
                key.workDate(),
                simulation.tenantId(),
                assignment.publicId(),
                simulation.commandEvidence().targetAuthorization().populationPublicId(),
                key.workDate().getDayOfWeek().getValue(),
                key.segmentKey(),
                key.workDate(), key.workDate(), key.workDate(), key.workDate());
        SegmentDefinition definition = exactlyOne(
                definitions, "removed segment evidence", key);
        return new SegmentEvidence(
                definition.kind(), null, null, definition.zoneId(), null, null,
                definition.overnight(), DstResolution.EXACT);
    }

    private SimulationResult loadResult(long tenantId, SimulationRow run) {
        List<PersistedSegment> persisted = jdbc.query("""
                SELECT a.public_id AS assignment_public_id,
                       s.segment_key,
                       s.change_kind,
                       s.segment_kind,
                       s.local_work_date,
                       s.start_at,
                       s.end_at,
                       s.zone_id,
                       s.start_offset_seconds,
                       s.end_offset_seconds,
                       s.overnight,
                       s.dst_resolution,
                       s.current_minutes,
                       s.draft_minutes
                  FROM tim_schedule_simulation_segments s
                  JOIN tim_work_plan_assignments a
                    ON a.tenant_id = s.tenant_id
                   AND a.work_plan_assignment_id = s.work_plan_assignment_id
                 WHERE s.tenant_id = ?
                   AND s.schedule_simulation_run_id = ?
                 ORDER BY s.local_work_date, a.public_id, s.segment_key
                """, JdbcWorkRegimeSimulationPersistence::mapPersistedSegment,
                tenantId, run.internalId());
        List<ResolvedSegment> segments = persisted.stream()
                .filter(row -> row.startAt() != null && row.endAt() != null)
                .map(PersistedSegment::resolved)
                .toList();
        List<ScheduleDiff> differences = persisted.stream()
                .map(PersistedSegment::difference)
                .toList();
        List<String> findings = parseFindings(run.findingsJson());
        if (findings.size() != run.findingCount()) {
            throw new IllegalStateException("Persisted simulation finding evidence is inconsistent");
        }
        return new SimulationResult(
                "SUCCEEDED".equals(run.state())
                        ? SimulationState.SUCCEEDED : SimulationState.BLOCKED,
                run.calculatedAt(),
                run.tzdbVersion(),
                run.policyRevision(),
                segments,
                differences,
                findings);
    }

    static void validate(SimulationWrite simulation) {
        Objects.requireNonNull(simulation, "simulation must not be null");
        Objects.requireNonNull(simulation.publicId(), "publicId must not be null");
        Objects.requireNonNull(simulation.receiptId(), "receiptId must not be null");
        Objects.requireNonNull(simulation.idempotencyKey(), "idempotencyKey must not be null");
        Objects.requireNonNull(
                simulation.workRegimePublicId(), "workRegimePublicId must not be null");
        Objects.requireNonNull(simulation.rulePackPublicId(), "rulePackPublicId must not be null");
        Objects.requireNonNull(simulation.period(), "period must not be null");
        if (simulation.period().to() == null) {
            throw new IllegalArgumentException("simulation requires a closed half-open period");
        }
        Objects.requireNonNull(simulation.result(), "result must not be null");
        Objects.requireNonNull(simulation.calculatedAt(), "calculatedAt must not be null");
        Objects.requireNonNull(
                simulation.commandEvidence(), "commandEvidence must not be null");
        if (!simulation.calculatedAt().equals(simulation.result().calculatedAt())) {
            throw new IllegalArgumentException("simulation calculation evidence does not match");
        }
        if (simulation.tenantId() <= 0 || simulation.policyRevision() < 1
                || simulation.actorId() <= 0) {
            throw new IllegalArgumentException("simulation tenant, revision and actor are invalid");
        }
    }

    private static void verifyIdentity(
            SimulationWrite simulation, SimulationRegime regime) {
        if (regime.policyRevision() != simulation.policyRevision()
                || !regime.rulePackPublicId().equals(simulation.rulePackPublicId())
                || !regime.resolutionDigest().equals(simulation.resolutionDigest())
                || !regime.zoneId().equals(simulation.zoneId())) {
            throw new IllegalArgumentException(
                    "Simulation evidence does not match its persisted work regime");
        }
    }

    private static Map<SimulationKey, ResolvedSegment> uniqueSegments(
            List<ResolvedSegment> segments) {
        Map<SimulationKey, ResolvedSegment> indexed = new HashMap<>();
        for (ResolvedSegment segment : segments) {
            SimulationKey key = new SimulationKey(
                    segment.assignmentId(), segment.localWorkDate(), segment.segmentKey());
            if (indexed.put(key, segment) != null) {
                throw new IllegalArgumentException("duplicate resolved simulation segment");
            }
        }
        return indexed;
    }

    private static Map<SimulationKey, ScheduleDiff> uniqueDifferences(
            List<ScheduleDiff> differences) {
        Map<SimulationKey, ScheduleDiff> indexed = new HashMap<>();
        for (ScheduleDiff difference : differences) {
            SimulationKey key = new SimulationKey(
                    difference.assignmentId(), difference.localWorkDate(), difference.segmentKey());
            if (indexed.put(key, difference) != null) {
                throw new IllegalArgumentException("duplicate simulation difference");
            }
        }
        return indexed;
    }

    private static String json(List<String> findings) {
        try {
            return JSON.writeValueAsString(findings);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("simulation findings are not serializable", exception);
        }
    }

    private static List<String> parseFindings(String value) {
        try {
            return List.copyOf(JSON.readValue(value, STRING_LIST));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("persisted simulation findings are invalid", exception);
        }
    }

    private static String ruleReference(SimulationWrite simulation) {
        return "RULE_PACK:" + simulation.rulePackPublicId() + ":"
                + simulation.policyRevision() + ":" + simulation.resolutionDigest();
    }

    private static Integer minutes(Long value) {
        return value == null ? null : Math.toIntExact(value);
    }

    private static long requireInternalId(Long id, String label) {
        if (id == null || id <= 0) {
            throw new IllegalStateException(label + " insert did not return an internal id");
        }
        return id;
    }

    private static SimulationRegime mapRegime(ResultSet row, int number) throws SQLException {
        return new SimulationRegime(
                row.getLong("work_regime_version_id"),
                row.getLong("policy_revision"),
                uuid(row, "rule_pack_public_id"),
                row.getString("resolution_digest").trim(),
                row.getString("default_zone_id"));
    }

    private static SimulationRow mapSimulationRow(ResultSet row, int number) throws SQLException {
        return new SimulationRow(
                row.getLong("schedule_simulation_run_id"),
                row.getString("lifecycle_state"),
                row.getString("result_digest").trim(),
                row.getTimestamp("calculated_at").toInstant(),
                row.getString("tzdb_version"),
                row.getLong("policy_revision"),
                row.getString("findings_json"),
                row.getInt("finding_count"),
                uuid(row, "work_regime_public_id"),
                row.getLong("expected_version"));
    }

    private static PersistedSegment mapPersistedSegment(ResultSet row, int number)
            throws SQLException {
        Timestamp start = row.getTimestamp("start_at");
        Timestamp end = row.getTimestamp("end_at");
        return new PersistedSegment(
                uuid(row, "assignment_public_id"),
                row.getString("segment_key"),
                DiffKind.valueOf(row.getString("change_kind")),
                SegmentKind.valueOf(row.getString("segment_kind")),
                row.getObject("local_work_date", LocalDate.class),
                start == null ? null : start.toInstant(),
                end == null ? null : end.toInstant(),
                row.getString("zone_id"),
                nullableInteger(row, "start_offset_seconds"),
                nullableInteger(row, "end_offset_seconds"),
                row.getBoolean("overnight"),
                DstResolution.valueOf(row.getString("dst_resolution")),
                nullableLong(row, "current_minutes"),
                nullableLong(row, "draft_minutes"));
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    private static <T> T exactlyOne(List<T> rows, String label, Object publicId) {
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one " + label + " for " + publicId + "; found " + rows.size());
        }
        return rows.getFirst();
    }

    private record SimulationRegime(
            long internalId,
            long policyRevision,
            UUID rulePackPublicId,
            String resolutionDigest,
            String zoneId) {
    }

    private record SimulationKey(UUID assignmentId, LocalDate workDate, String segmentKey)
            implements Comparable<SimulationKey> {
        @Override
        public int compareTo(SimulationKey other) {
            int assignment = assignmentId.toString().compareTo(other.assignmentId.toString());
            if (assignment != 0) return assignment;
            int date = workDate.compareTo(other.workDate);
            if (date != 0) return date;
            return segmentKey.compareTo(other.segmentKey);
        }
    }

    private record SegmentEvidence(
            SegmentKind kind,
            Instant startAt,
            Instant endAt,
            String zoneId,
            Integer startOffsetSeconds,
            Integer endOffsetSeconds,
            boolean overnight,
            DstResolution dstResolution) {

        static SegmentEvidence from(ResolvedSegment segment) {
            return new SegmentEvidence(
                    segment.kind(),
                    segment.startAt(),
                    segment.endAt(),
                    segment.zoneId(),
                    segment.startOffset().getTotalSeconds(),
                    segment.endOffset().getTotalSeconds(),
                    segment.overnight(),
                    segment.dstResolution());
        }
    }

    private record SegmentDefinition(
            String key, SegmentKind kind, boolean overnight, String zoneId) {
    }

    private record SimulationRow(
            long internalId,
            String state,
            String resultDigest,
            Instant calculatedAt,
            String tzdbVersion,
            long policyRevision,
            String findingsJson,
            int findingCount,
            UUID workRegimePublicId,
            long baseVersion) {
    }

    private record PersistedSegment(
            UUID assignmentId,
            String segmentKey,
            DiffKind changeKind,
            SegmentKind segmentKind,
            LocalDate workDate,
            Instant startAt,
            Instant endAt,
            String zoneId,
            Integer startOffsetSeconds,
            Integer endOffsetSeconds,
            boolean overnight,
            DstResolution dstResolution,
            Long currentMinutes,
            Long draftMinutes) {

        ResolvedSegment resolved() {
            return new ResolvedSegment(
                    assignmentId,
                    segmentKey,
                    segmentKind,
                    workDate,
                    startAt,
                    endAt,
                    zoneId,
                    ZoneOffset.ofTotalSeconds(
                            Objects.requireNonNull(startOffsetSeconds, "start offset is missing")),
                    ZoneOffset.ofTotalSeconds(
                            Objects.requireNonNull(endOffsetSeconds, "end offset is missing")),
                    overnight,
                    dstResolution,
                    Objects.requireNonNull(draftMinutes, "draft minutes are missing"));
        }

        ScheduleDiff difference() {
            return new ScheduleDiff(
                    assignmentId,
                    workDate,
                    segmentKey,
                    changeKind,
                    currentMinutes,
                    draftMinutes);
        }
    }
}
