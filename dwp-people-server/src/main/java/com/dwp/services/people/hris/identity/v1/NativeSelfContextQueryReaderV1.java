package com.dwp.services.people.hris.identity.v1;

import com.dwp.platform.contracts.hris.identity.v1.NativeSelfContextSetV1;
import com.dwp.platform.contracts.hris.identity.v1.SelfContextContractExceptionV1;
import com.dwp.platform.contracts.hris.identity.v1.SelfContextOwnerPortsV1;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.identity.v1.SelfContextContractExceptionV1.Code.*;

/**
 * Single-statement, tenant-bound People owner read over existing native identities.
 * Complete means all applicable native assignment keys, not the primary/LIMIT1 assignment.
 * Location's explicitly configured work zone is required: no person/display/global zone fallback.
 * Equal-day effective corrections use native sequence; overlapping different-day slices fail closed.
 * Unregistered: runtime authority, signed transport, lifecycle ordering and production wiring remain OPEN.
 */
public final class NativeSelfContextQueryReaderV1 implements SelfContextOwnerPortsV1.NativeContextProvider {
    private static final int MAX_CONTEXTS = 100;
    private static final int QUERY_TIMEOUT_SECONDS = 5;
    private static final String SQL = """
            SELECT person.tenant_id, person.public_id AS person_public_id,
                   person.version AS person_version, person.lifecycle_state AS person_state,
                   worker.public_id AS worker_public_id, worker.version AS worker_version,
                   worker.worker_status,
                   relationship.public_id AS relationship_public_id,
                   relationship.version AS relationship_version,
                   relationship.start_date, relationship.end_date,
                   employer.public_id AS employer_public_id,
                   assignment.public_id AS assignment_public_id,
                   assignment.assignment_key, assignment.version AS assignment_version,
                   assignment.assignment_status, assignment.effective_start_date,
                   assignment.effective_end_date, location.time_zone AS work_zone
              FROM public.ppl_persons person
              LEFT JOIN public.ppl_workers worker
                ON worker.tenant_id = person.tenant_id AND worker.person_id = person.person_id
              LEFT JOIN public.ppl_work_relationships relationship
                ON relationship.tenant_id = worker.tenant_id AND relationship.worker_id = worker.worker_id
              LEFT JOIN public.ppl_legal_employers employer
                ON employer.tenant_id = relationship.tenant_id
               AND employer.legal_employer_id = relationship.legal_employer_id
              LEFT JOIN public.ppl_assignments assignment
                ON assignment.tenant_id = relationship.tenant_id
               AND assignment.work_relationship_id = relationship.work_relationship_id
              LEFT JOIN public.ppl_locations location
                ON location.tenant_id = assignment.tenant_id AND location.location_id = assignment.location_id
             WHERE person.tenant_id = ? AND person.public_id = ?
               AND (assignment.assignment_id IS NULL OR location.time_zone IS NULL OR (
                   assignment.effective_start_date <= timezone(location.time_zone, CAST(? AS TIMESTAMPTZ))::date
                   AND (assignment.effective_end_date IS NULL OR
                        assignment.effective_end_date >= timezone(location.time_zone, CAST(? AS TIMESTAMPTZ))::date)))
               AND (assignment.assignment_id IS NULL OR NOT EXISTS (
                   SELECT 1 FROM public.ppl_assignments correction
                    WHERE correction.tenant_id = assignment.tenant_id
                      AND correction.assignment_key = assignment.assignment_key
                      AND correction.effective_start_date = assignment.effective_start_date
                      AND correction.effective_sequence > assignment.effective_sequence))
             ORDER BY worker.public_id, relationship.public_id, assignment.public_id
             LIMIT 101
            """;

    private final DataSource runtimeDataSource;

    public NativeSelfContextQueryReaderV1(DataSource runtimeDataSource) {
        this.runtimeDataSource = Objects.requireNonNull(runtimeDataSource);
    }

    @Override
    public NativeSelfContextSetV1 loadComplete(SelfContextOwnerPortsV1.PeopleLookup request) {
        if (request == null || request.binding() == null || request.query() == null
                || request.query().asOf() == null) throw new SelfContextContractExceptionV1(QUERY_INVALID);
        var binding = request.binding();
        var contexts = new ArrayList<NativeSelfContextSetV1.EmploymentContext>();
        var assignmentKeys = new HashSet<String>();
        try (Connection connection = runtimeDataSource.getConnection()) {
            connection.setReadOnly(true);
            try (var statement = connection.prepareStatement(SQL)) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.setLong(1, binding.tenantId());
                statement.setObject(2, binding.personPublicId());
                var asOf = OffsetDateTime.ofInstant(request.query().asOf(), ZoneOffset.UTC);
                statement.setObject(3, asOf);
                statement.setObject(4, asOf);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new SelfContextContractExceptionV1(SELF_SCOPE_UNRESOLVED);
                    Long personVersion = rows.getObject("person_version", Long.class);
                    if (personVersion == null) throw new SelfContextContractExceptionV1(OWNER_RESPONSE_INVALID);
                    long tenantId = rows.getLong("tenant_id");
                    UUID personId = rows.getObject("person_public_id", UUID.class);
                    var personState = NativeSelfContextSetV1.PersonState.valueOf(rows.getString("person_state"));
                    int rowCount = 0;
                    do {
                        rowCount++;
                        UUID assignmentId = rows.getObject("assignment_public_id", UUID.class);
                        if (assignmentId == null) continue;
                        String key = rows.getString("assignment_key");
                        if (key == null || !assignmentKeys.add(key)) {
                            throw new SelfContextContractExceptionV1(OWNER_RESPONSE_INVALID);
                        }
                        UUID workerId = rows.getObject("worker_public_id", UUID.class);
                        UUID relationshipId = rows.getObject("relationship_public_id", UUID.class);
                        contexts.add(new NativeSelfContextSetV1.EmploymentContext(
                                new NativeSelfContextSetV1.Worker(workerId, personId,
                                        requiredVersion(rows, "worker_version"), rows.getString("worker_status")),
                                new NativeSelfContextSetV1.WorkRelationship(relationshipId, workerId,
                                        rows.getObject("employer_public_id", UUID.class),
                                        requiredVersion(rows, "relationship_version"),
                                        rows.getObject("start_date", java.time.LocalDate.class),
                                        rows.getObject("end_date", java.time.LocalDate.class)),
                                new NativeSelfContextSetV1.Assignment(assignmentId, relationshipId,
                                        requiredVersion(rows, "assignment_version"), rows.getString("assignment_status"),
                                        rows.getObject("effective_start_date", java.time.LocalDate.class),
                                        rows.getObject("effective_end_date", java.time.LocalDate.class),
                                        ZoneId.of(rows.getString("work_zone")))));
                    } while (rows.next());
                    return new NativeSelfContextSetV1(tenantId, personId, personVersion, personState,
                            request.query().asOf(), rowCount <= MAX_CONTEXTS, contexts);
                }
            }
        } catch (SelfContextContractExceptionV1 rejected) {
            throw rejected;
        } catch (IllegalArgumentException | NullPointerException | DateTimeException malformedNativeRow) {
            throw new SelfContextContractExceptionV1(OWNER_RESPONSE_INVALID);
        } catch (SQLException unavailable) {
            throw new SelfContextContractExceptionV1(OWNER_UNAVAILABLE);
        }
    }

    private long requiredVersion(java.sql.ResultSet rows, String column) throws SQLException {
        Long value = rows.getObject(column, Long.class);
        if (value == null) throw new SelfContextContractExceptionV1(OWNER_RESPONSE_INVALID);
        return value;
    }
}
