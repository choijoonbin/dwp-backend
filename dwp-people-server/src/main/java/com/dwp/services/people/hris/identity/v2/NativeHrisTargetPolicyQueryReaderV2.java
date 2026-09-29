package com.dwp.services.people.hris.identity.v2;

import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationPortsV2.PeopleLookup;
import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationV2.TargetKind;
import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationV2.TargetSnapshot;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import static com.dwp.services.people.hris.identity.v2.NativeHrisTargetPolicyInputsV2.*;
import static com.dwp.services.people.hris.identity.v2.NativeHrisTargetPolicyInputsV2.Code.*;

/** Fixed owner catalog reads, one repeatable-read READ ONLY transaction; no arbitrary SQL port. */
public final class NativeHrisTargetPolicyQueryReaderV2 implements InputsProvider {
    private static final String PERSON = """
            SELECT tenant_id, public_id, version, lifecycle_state FROM public.ppl_persons
             WHERE tenant_id = ? AND public_id = ?
            """;
    private static final String EMPLOYMENT = """
            SELECT p.tenant_id, p.public_id person_id, p.version person_version, p.lifecycle_state,
                   w.public_id worker_id, w.version worker_version, w.worker_status,
                   r.public_id relationship_id, r.version relationship_version, r.start_date, r.end_date,
                   a.public_id assignment_id, a.version assignment_version, a.assignment_status,
                   a.effective_start_date, a.effective_end_date, a.assignment_key, a.effective_sequence,
                   o.public_id organization_id,
                   (SELECT COUNT(*) FROM public.ppl_assignments other
                     WHERE other.tenant_id = a.tenant_id AND other.assignment_key = a.assignment_key
                       AND other.effective_start_date <= ?
                       AND (other.effective_end_date IS NULL OR other.effective_end_date >= ?)
                       AND NOT EXISTS (SELECT 1 FROM public.ppl_assignments correction
                            WHERE correction.tenant_id = other.tenant_id
                              AND correction.assignment_key = other.assignment_key
                              AND correction.effective_start_date = other.effective_start_date
                              AND correction.effective_sequence > other.effective_sequence)) applicable_slices
              FROM public.ppl_persons p
              JOIN public.ppl_workers w ON w.tenant_id = p.tenant_id AND w.person_id = p.person_id
              JOIN public.ppl_work_relationships r ON r.tenant_id = w.tenant_id AND r.worker_id = w.worker_id
              JOIN public.ppl_assignments a ON a.tenant_id = r.tenant_id
                   AND a.work_relationship_id = r.work_relationship_id
              LEFT JOIN public.ppl_organizations o ON o.tenant_id = a.tenant_id AND o.organization_id = a.organization_id
             WHERE p.tenant_id = ? AND p.public_id = ? AND w.public_id = ? AND r.public_id = ? AND a.public_id = ?
               AND NOT EXISTS (SELECT 1 FROM public.ppl_assignments correction
                    WHERE correction.tenant_id = a.tenant_id AND correction.assignment_key = a.assignment_key
                      AND correction.effective_start_date = a.effective_start_date
                      AND correction.effective_sequence > a.effective_sequence)
            """;
    private static final String ANCESTORS = """
            WITH RECURSIVE ancestors AS (
                SELECT organization_id, public_id, parent_organization_id, version, lifecycle_state
                  FROM public.ppl_organizations WHERE tenant_id = ? AND public_id = ?
                UNION
                SELECT parent.organization_id, parent.public_id, parent.parent_organization_id,
                       parent.version, parent.lifecycle_state
                  FROM public.ppl_organizations parent JOIN ancestors child
                    ON parent.organization_id = child.parent_organization_id WHERE parent.tenant_id = ?)
            SELECT child.public_id, child.version, child.lifecycle_state, parent.public_id parent_id
              FROM ancestors child LEFT JOIN public.ppl_organizations parent
                ON parent.tenant_id = ? AND parent.organization_id = child.parent_organization_id
             ORDER BY child.public_id LIMIT 101
            """;
    private static final String POLICIES = """
            SELECT policy.workforce_access_policy_id, policy.tenant_id, policy.subject_type,
                   policy.subject_ref, policy.population_type, policy.organization_public_id,
                   organization.public_id resolved_organization, policy.field_groups, policy.action_codes,
                   policy.valid_from, policy.valid_to, policy.lifecycle_state, policy.version
              FROM public.ppl_workforce_access_policies policy
              LEFT JOIN public.ppl_organizations organization ON organization.tenant_id = policy.tenant_id
                   AND organization.public_id = policy.organization_public_id AND organization.lifecycle_state = 'ACTIVE'
             WHERE policy.tenant_id = ? AND policy.lifecycle_state = 'ACTIVE'
               AND (policy.valid_from IS NULL OR policy.valid_from <= ?)
               AND (policy.valid_to IS NULL OR policy.valid_to > ?)
               AND ((policy.subject_type = 'USER' AND policy.subject_ref = ?)
            """;
    private final DataSource runtimeDataSource;
    public NativeHrisTargetPolicyQueryReaderV2(DataSource runtimeDataSource) {
        this.runtimeDataSource = Objects.requireNonNull(runtimeDataSource);
    }

    @Override
    public Inputs read(PeopleLookup lookup, Set<String> roleCodes, Instant asOf, LocalDate date) {
        if (lookup == null || lookup.authority() == null || lookup.selector() == null || roleCodes == null
                || roleCodes.size() > 100 || asOf == null) throw new Rejected(CONTEXT_INVALID);
        try (Connection connection = runtimeDataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                Inputs target = lookup.selector().kind() == TargetKind.PERSON
                        ? person(connection, lookup) : employment(connection, lookup, date);
                List<Organization> ancestors = target.organizationPublicId() == null ? List.of()
                        : ancestors(connection, lookup.authority().actor().tenantId(), target.organizationPublicId());
                var inputs = new Inputs(target.target(), target.workerState(), target.relationshipStart(),
                        target.relationshipEnd(), target.assignmentState(), target.assignmentStart(),
                        target.assignmentEnd(), target.assignmentKey(), target.effectiveSequence(),
                        target.organizationPublicId(), ancestors, policies(connection, lookup, roleCodes, asOf));
                connection.commit();
                return inputs;
            } finally { if (!connection.isClosed()) connection.rollback(); }
        } catch (Rejected rejected) { throw rejected;
        } catch (SQLException unavailable) { throw new Rejected(UNAVAILABLE);
        } catch (RuntimeException malformedNativeRow) { throw new Rejected(NATIVE_INVALID); }
    }

    private Inputs person(Connection c, PeopleLookup lookup) throws SQLException {
        try (var statement = c.prepareStatement(PERSON)) {
            statement.setQueryTimeout(5); statement.setLong(1, lookup.authority().actor().tenantId());
            statement.setObject(2, lookup.selector().personPublicId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new Rejected(TARGET_NOT_FOUND);
                var target = new TargetSnapshot(TargetKind.PERSON, rows.getLong("tenant_id"),
                        rows.getObject("public_id", UUID.class), version(rows, "version"), rows.getString("lifecycle_state"),
                        null, null, null, null, null, null, null, null, null);
                if (rows.next()) throw new Rejected(NATIVE_INVALID);
                return new Inputs(target, null, null, null, null, null, null, null, 0, null, List.of(), List.of());
            }
        }
    }
    private Inputs employment(Connection c, PeopleLookup lookup, LocalDate date) throws SQLException {
        if (date == null) throw new Rejected(DATE_POLICY_UNAVAILABLE);
        var selector = lookup.selector();
        try (var statement = c.prepareStatement(EMPLOYMENT)) {
            statement.setQueryTimeout(5); statement.setObject(1, date); statement.setObject(2, date);
            statement.setLong(3, lookup.authority().actor().tenantId()); statement.setObject(4, selector.personPublicId());
            statement.setObject(5, selector.workerPublicId()); statement.setObject(6, selector.workRelationshipPublicId());
            statement.setObject(7, selector.assignmentPublicId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new Rejected(TARGET_NOT_FOUND);
                UUID person = rows.getObject("person_id", UUID.class), worker = rows.getObject("worker_id", UUID.class);
                UUID relationship = rows.getObject("relationship_id", UUID.class);
                var target = new TargetSnapshot(TargetKind.EMPLOYMENT, rows.getLong("tenant_id"), person,
                        version(rows, "person_version"), rows.getString("lifecycle_state"), worker, person,
                        version(rows, "worker_version"), relationship, worker, version(rows, "relationship_version"),
                        rows.getObject("assignment_id", UUID.class), relationship, version(rows, "assignment_version"));
                if (rows.getLong("applicable_slices") != 1) throw new Rejected(NATIVE_INVALID);
                var result = new Inputs(target, rows.getString("worker_status"), rows.getObject("start_date", LocalDate.class),
                        rows.getObject("end_date", LocalDate.class), rows.getString("assignment_status"),
                        rows.getObject("effective_start_date", LocalDate.class), rows.getObject("effective_end_date", LocalDate.class),
                        rows.getString("assignment_key"), rows.getInt("effective_sequence"),
                        rows.getObject("organization_id", UUID.class), List.of(), List.of());
                if (rows.next()) throw new Rejected(NATIVE_INVALID);
                return result;
            }
        }
    }
    private List<Organization> ancestors(Connection c, long tenant, UUID organization) throws SQLException {
        try (var statement = c.prepareStatement(ANCESTORS)) {
            statement.setQueryTimeout(5); statement.setLong(1, tenant); statement.setObject(2, organization);
            statement.setLong(3, tenant); statement.setLong(4, tenant);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<Organization>();
                while (rows.next()) {
                    if (result.size() == 100) throw new Rejected(NATIVE_INVALID);
                    result.add(new Organization(rows.getObject("public_id", UUID.class), version(rows, "version"),
                            rows.getObject("parent_id", UUID.class), rows.getString("lifecycle_state")));
                }
                return result;
            }
        }
    }
    private List<Policy> policies(Connection c, PeopleLookup lookup, Set<String> roles, Instant asOf) throws SQLException {
        var sortedRoles = roles.stream().sorted().toList();
        String subject = sortedRoles.isEmpty() ? "" : " OR (policy.subject_type = 'ROLE' AND policy.subject_ref IN ("
                + String.join(",", java.util.Collections.nCopies(sortedRoles.size(), "?")) + "))";
        try (var statement = c.prepareStatement(POLICIES + subject + ") ORDER BY policy.workforce_access_policy_id LIMIT 101")) {
            statement.setQueryTimeout(5); statement.setLong(1, lookup.authority().actor().tenantId());
            statement.setTimestamp(2, Timestamp.from(asOf)); statement.setTimestamp(3, Timestamp.from(asOf));
            statement.setString(4, Long.toString(lookup.authority().actor().userId()));
            for (int i = 0; i < sortedRoles.size(); i++) statement.setString(5 + i, sortedRoles.get(i));
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<Policy>();
                while (rows.next()) {
                    if (result.size() == 100) throw new Rejected(NATIVE_INVALID);
                    result.add(new Policy(rows.getObject("workforce_access_policy_id", UUID.class), rows.getLong("tenant_id"),
                            rows.getString("subject_type"), rows.getString("subject_ref"), rows.getString("population_type"),
                            rows.getObject("organization_public_id", UUID.class), rows.getObject("resolved_organization", UUID.class) != null,
                            array(rows, "field_groups"), array(rows, "action_codes"), instant(rows, "valid_from"), instant(rows, "valid_to"),
                            rows.getString("lifecycle_state"), version(rows, "version")));
                }
                return result;
            }
        }
    }
    private Set<String> array(ResultSet rows, String column) throws SQLException {
        var value = rows.getArray(column);
        if (value == null) throw new Rejected(NATIVE_INVALID);
        try { return Set.copyOf(Arrays.asList((String[]) value.getArray())); } finally { value.free(); }
    }
    private Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    private long version(ResultSet rows, String column) throws SQLException {
        Long value = rows.getObject(column, Long.class);
        if (value == null || value < 0) throw new Rejected(NATIVE_INVALID); return value;
    }
}
