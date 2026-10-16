package com.dwp.services.people.hris.home;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Tenant- and owner-population-bound source for the closed People Home profile. */
@Repository
@ConditionalOnProperty(prefix = PeopleHomeAuthorityPort.PREFIX,
        name = "enabled", havingValue = "true", matchIfMissing = false)
final class JdbcPeopleHomeSource implements PeopleHomeSourcePort {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcPeopleHomeSource(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Snapshot snapshot(PeopleHomeAuthorityPort.CurrentAuthority authority) {
        bindTenant(authority.tenantId());
        Set<UUID> population = authority.populationPersonPublicIds();
        List<Row> rows = jdbc.query("""
                SELECT person.public_id AS person_public_id,
                       worker.worker_status,
                       organization.name AS organization_name,
                       GREATEST(person.version, COALESCE(worker.version, 0),
                                COALESCE(assignment.version, 0),
                                COALESCE(organization.version, 0)) AS source_version,
                       GREATEST(person.updated_at, COALESCE(worker.updated_at, person.updated_at),
                                COALESCE(assignment.updated_at, person.updated_at),
                                COALESCE(organization.updated_at, person.updated_at)) AS updated_at
                  FROM ppl_persons person
             LEFT JOIN LATERAL (
                       SELECT candidate.worker_id, candidate.worker_status,
                              candidate.version, candidate.updated_at
                         FROM ppl_workers candidate
                        WHERE candidate.tenant_id = person.tenant_id
                          AND candidate.person_id = person.person_id
                        ORDER BY candidate.updated_at DESC, candidate.worker_id DESC
                        LIMIT 1
                  ) worker ON TRUE
             LEFT JOIN LATERAL (
                       SELECT candidate.organization_id, candidate.version,
                              candidate.updated_at
                         FROM ppl_work_relationships relationship
                         JOIN ppl_assignments candidate
                           ON candidate.tenant_id = relationship.tenant_id
                          AND candidate.work_relationship_id = relationship.work_relationship_id
                        WHERE relationship.tenant_id = person.tenant_id
                          AND relationship.worker_id = worker.worker_id
                          AND candidate.primary_assignment = TRUE
                          AND candidate.assignment_status IN ('ACTIVE','SUSPENDED','PENDING')
                          AND candidate.effective_start_date <= CURRENT_DATE
                          AND (candidate.effective_end_date IS NULL
                               OR candidate.effective_end_date >= CURRENT_DATE)
                        ORDER BY candidate.effective_start_date DESC,
                                 candidate.effective_sequence DESC,
                                 candidate.assignment_id DESC
                        LIMIT 1
                  ) assignment ON TRUE
             LEFT JOIN ppl_organizations organization
                    ON organization.tenant_id = person.tenant_id
                   AND organization.organization_id = assignment.organization_id
                 WHERE person.tenant_id = :tenant
                   AND person.public_id IN (:population)
                 ORDER BY person.public_id
                """, new MapSqlParameterSource()
                .addValue("tenant", authority.tenantId())
                .addValue("population", population),
                (result, ignored) -> new Row(
                        result.getObject("person_public_id", UUID.class),
                        result.getString("worker_status"),
                        result.getString("organization_name"),
                        result.getLong("source_version"),
                        result.getTimestamp("updated_at").toInstant()));

        if (rows.size() != population.size()) {
            return new Snapshot(Optional.empty(), Optional.empty(), Optional.empty(),
                    Math.max(1, rows.stream().mapToLong(Row::sourceVersion).max().orElse(0)),
                    rows.stream().map(Row::updatedAt).max(Comparator.naturalOrder())
                            .orElse(Instant.EPOCH));
        }
        long pending = jdbc.queryForObject("""
                SELECT LEAST(COUNT(*), 999)
                  FROM ppl_employee_change_requests request
                  JOIN ppl_persons target
                    ON target.tenant_id = request.tenant_id
                   AND target.person_id = request.target_person_id
                 WHERE request.tenant_id = :tenant
                   AND target.public_id IN (:population)
                   AND request.lifecycle_state IN (
                       'SUBMITTED','IN_REVIEW','APPROVED','APPLYING','APPLY_FAILED')
                """, new MapSqlParameterSource()
                .addValue("tenant", authority.tenantId())
                .addValue("population", population), Long.class);

        Optional<String> employment = rows.stream().anyMatch(row -> row.workerStatus() == null)
                ? Optional.empty() : Optional.of(aggregateEmployment(rows));
        String organizationLabel = rows.stream()
                .anyMatch(row -> row.organizationName() == null)
                ? null : aggregateOrganization(rows);
        Optional<String> organization = safeText(organizationLabel)
                ? Optional.of(organizationLabel) : Optional.empty();
        long sourceVersion = Math.max(1,
                rows.stream().mapToLong(Row::sourceVersion).max().orElse(0));
        Instant asOf = rows.stream().map(Row::updatedAt).max(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
        return new Snapshot(employment, organization, Optional.of(pending),
                sourceVersion, asOf);
    }

    private void bindTenant(long tenantId) {
        String expected = Long.toString(tenantId);
        String configured = jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', :tenantId, true)",
                new MapSqlParameterSource("tenantId", expected), String.class);
        if (!expected.equals(configured)) {
            throw new IllegalStateException("People Home tenant context was not established.");
        }
    }

    private static String aggregateEmployment(List<Row> rows) {
        List<String> values = rows.stream().map(row -> switch (row.workerStatus()) {
            case "ACTIVE" -> "ACTIVE";
            case "LEAVE" -> "LEAVE";
            case "PENDING" -> "NOTICE";
            default -> "INACTIVE";
        }).distinct().toList();
        if (values.size() == 1) return values.getFirst();
        if (values.contains("ACTIVE")) return "ACTIVE";
        if (values.contains("LEAVE")) return "LEAVE";
        if (values.contains("NOTICE")) return "NOTICE";
        return "INACTIVE";
    }

    private static String aggregateOrganization(List<Row> rows) {
        List<String> labels = new ArrayList<>(rows.stream()
                .map(Row::organizationName).distinct().sorted().toList());
        return labels.size() == 1 ? labels.getFirst() : "Multiple organizations";
    }

    private static boolean safeText(String value) {
        return value != null && !value.isBlank() && value.length() <= 120
                && value.equals(value.strip()) && value.indexOf('<') < 0
                && value.indexOf('>') < 0
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private record Row(
            UUID personPublicId,
            String workerStatus,
            String organizationName,
            long sourceVersion,
            Instant updatedAt) {
    }
}
