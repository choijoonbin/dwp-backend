package com.dwp.services.time.workregime;

import com.dwp.services.time.workregime.WorkRegimeRepository.SimulationWrite;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Loads and verifies the target and published worker context used by a simulation result. */
final class JdbcWorkRegimeSimulationAssignmentSupport {

    private final JdbcTemplate jdbc;

    JdbcWorkRegimeSimulationAssignmentSupport(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    Map<UUID, SimulationAssignment> load(SimulationWrite simulation, long regimeId) {
        Set<TargetAssignmentIdentity> targets = new TreeSet<>();
        targets.addAll(jdbc.query("""
                SELECT a.worker_public_id,
                       a.people_assignment_public_id
                  FROM tim_work_plan_assignments a
                  JOIN tim_work_regime_versions wr
                    ON wr.tenant_id = a.tenant_id
                   AND wr.work_regime_version_id = a.work_regime_version_id
                   AND wr.policy_revision = a.policy_revision
                 WHERE a.tenant_id = ?
                   AND a.work_regime_version_id = ?
                   AND a.lifecycle_state IN ('DRAFT', 'PUBLISHED')
                   AND wr.lifecycle_state IN (
                       'DRAFT', 'VALIDATED', 'SIMULATED',
                       'IN_REVIEW', 'APPROVED', 'PUBLISHED'
                   )
                   AND a.effective_from <= ?
                   AND (a.effective_to IS NULL OR a.effective_to >= ?)
                   AND wr.effective_from <= ?
                   AND (wr.effective_to IS NULL OR wr.effective_to >= ?)
                 ORDER BY a.worker_public_id, a.people_assignment_public_id
                """, JdbcWorkRegimeSimulationAssignmentSupport::mapTargetAssignment,
                simulation.tenantId(),
                regimeId,
                simulation.period().from(),
                simulation.period().to(),
                simulation.period().from(),
                simulation.period().to()));
        if (targets.isEmpty()) {
            throw new IllegalArgumentException(
                    "Simulation work regime has no effective target assignment");
        }

        Set<UUID> publicIds = new TreeSet<>(Comparator.comparing(UUID::toString));
        simulation.result().segments().forEach(segment -> publicIds.add(segment.assignmentId()));
        simulation.result().differences().forEach(diff -> publicIds.add(diff.assignmentId()));
        Map<UUID, SimulationAssignment> assignments = new LinkedHashMap<>();
        for (UUID publicId : publicIds) {
            SimulationAssignment assignment = exactlyOne(jdbc.query("""
                    SELECT a.work_plan_assignment_id,
                           a.public_id,
                           a.worker_public_id,
                           a.people_assignment_public_id,
                           a.work_regime_version_id,
                           a.lifecycle_state AS assignment_state,
                           a.zone_id,
                           wr.lifecycle_state AS regime_state,
                           a.effective_from AS assignment_from,
                           a.effective_to AS assignment_to,
                           wr.effective_from AS regime_from,
                           wr.effective_to AS regime_to
                      FROM tim_work_plan_assignments a
                      JOIN tim_work_regime_versions wr
                        ON wr.tenant_id = a.tenant_id
                       AND wr.work_regime_version_id = a.work_regime_version_id
                       AND wr.policy_revision = a.policy_revision
                     WHERE a.tenant_id = ?
                       AND a.public_id = ?
                    """, JdbcWorkRegimeSimulationAssignmentSupport::mapAssignment,
                    simulation.tenantId(),
                    publicId),
                    "simulation assignment", publicId);
            verifyProvenance(assignment, regimeId, targets, simulation);
            verifyResultDates(assignment, simulation);
            assignments.put(publicId, assignment);
        }
        return assignments;
    }

    private static void verifyProvenance(
            SimulationAssignment assignment,
            long regimeId,
            Set<TargetAssignmentIdentity> targets,
            SimulationWrite simulation) {
        TargetAssignmentIdentity identity = new TargetAssignmentIdentity(
                assignment.workerPublicId(), assignment.peopleAssignmentPublicId());
        boolean target = assignment.workRegimeVersionId() == regimeId;
        boolean publishedWorkerContext = !targets.contains(identity)
                && targets.stream().anyMatch(candidate ->
                        candidate.workerPublicId().equals(assignment.workerPublicId()));
        boolean targetCoversPeriod = covers(
                assignment.assignmentFrom(), assignment.assignmentTo(),
                simulation.period().from(), simulation.period().to())
                && covers(
                        assignment.regimeFrom(), assignment.regimeTo(),
                        simulation.period().from(), simulation.period().to());
        boolean contextIntersectsPeriod = intersects(
                assignment.assignmentFrom(), assignment.assignmentTo(),
                simulation.period().from(), simulation.period().to())
                && intersects(
                        assignment.regimeFrom(), assignment.regimeTo(),
                        simulation.period().from(), simulation.period().to())
                && intersects(
                        assignment.assignmentFrom(), assignment.assignmentTo(),
                        assignment.regimeFrom(), assignment.regimeTo());
        if (target && (!targets.contains(identity) || !targetCoversPeriod
                || !("DRAFT".equals(assignment.assignmentState())
                        || "PUBLISHED".equals(assignment.assignmentState()))
                || !("DRAFT".equals(assignment.regimeState())
                        || "VALIDATED".equals(assignment.regimeState())
                        || "SIMULATED".equals(assignment.regimeState())
                        || "IN_REVIEW".equals(assignment.regimeState())
                        || "APPROVED".equals(assignment.regimeState())
                        || "PUBLISHED".equals(assignment.regimeState())))) {
            throw new IllegalArgumentException(
                    "Simulation target assignment is not effective for the selected work regime");
        }
        if (!target && (!publishedWorkerContext || !contextIntersectsPeriod
                || !"PUBLISHED".equals(assignment.assignmentState())
                || !"PUBLISHED".equals(assignment.regimeState()))) {
            throw new IllegalArgumentException(
                    "Simulation context assignment is not an effective published assignment "
                            + "for the target worker");
        }
    }

    static boolean covers(
            LocalDate from, LocalDate to, LocalDate requestedFrom, LocalDate requestedTo) {
        return !from.isAfter(requestedFrom) && (to == null || !to.isBefore(requestedTo));
    }

    static boolean intersects(
            LocalDate leftFrom, LocalDate leftTo, LocalDate rightFrom, LocalDate rightTo) {
        return (rightTo == null || leftFrom.isBefore(rightTo))
                && (leftTo == null || rightFrom.isBefore(leftTo));
    }

    private static void verifyResultDates(
            SimulationAssignment assignment, SimulationWrite simulation) {
        boolean invalidSegment = simulation.result().segments().stream()
                .filter(segment -> segment.assignmentId().equals(assignment.publicId()))
                .anyMatch(segment -> !activeOn(assignment, simulation, segment.localWorkDate()));
        boolean invalidDifference = simulation.result().differences().stream()
                .filter(difference -> difference.assignmentId().equals(assignment.publicId()))
                .anyMatch(difference -> !activeOn(
                        assignment, simulation, difference.localWorkDate()));
        if (invalidSegment || invalidDifference) {
            throw new IllegalArgumentException(
                    "Simulation result date is outside its assignment effective period");
        }
    }

    private static boolean activeOn(
            SimulationAssignment assignment, SimulationWrite simulation, LocalDate date) {
        return contains(assignment.assignmentFrom(), assignment.assignmentTo(), date)
                && contains(assignment.regimeFrom(), assignment.regimeTo(), date)
                && contains(simulation.period().from(), simulation.period().to(), date);
    }

    static boolean contains(LocalDate from, LocalDate to, LocalDate date) {
        return !date.isBefore(from) && (to == null || date.isBefore(to));
    }

    private static SimulationAssignment mapAssignment(ResultSet row, int number)
            throws SQLException {
        return new SimulationAssignment(
                row.getLong("work_plan_assignment_id"),
                uuid(row, "public_id"),
                uuid(row, "worker_public_id"),
                uuid(row, "people_assignment_public_id"),
                row.getLong("work_regime_version_id"),
                row.getString("assignment_state"),
                row.getString("regime_state"),
                row.getString("zone_id"),
                row.getObject("assignment_from", LocalDate.class),
                row.getObject("assignment_to", LocalDate.class),
                row.getObject("regime_from", LocalDate.class),
                row.getObject("regime_to", LocalDate.class));
    }

    private static TargetAssignmentIdentity mapTargetAssignment(ResultSet row, int number)
            throws SQLException {
        return new TargetAssignmentIdentity(
                uuid(row, "worker_public_id"),
                uuid(row, "people_assignment_public_id"));
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    private static <T> T exactlyOne(List<T> rows, String label, Object publicId) {
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one " + label + " for " + publicId + "; found " + rows.size());
        }
        return rows.getFirst();
    }

    record SimulationAssignment(
            long internalId,
            UUID publicId,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long workRegimeVersionId,
            String assignmentState,
            String regimeState,
            String zoneId,
            LocalDate assignmentFrom,
            LocalDate assignmentTo,
            LocalDate regimeFrom,
            LocalDate regimeTo) {
    }

    private record TargetAssignmentIdentity(
            UUID workerPublicId, UUID peopleAssignmentPublicId)
            implements Comparable<TargetAssignmentIdentity> {
        @Override
        public int compareTo(TargetAssignmentIdentity other) {
            int worker = workerPublicId.toString().compareTo(other.workerPublicId().toString());
            return worker != 0
                    ? worker
                    : peopleAssignmentPublicId.toString().compareTo(
                            other.peopleAssignmentPublicId().toString());
        }
    }
}
