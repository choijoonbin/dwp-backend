package com.dwp.services.people.hris.workbench.hrm011;

import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractDtos;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** RLS-bound SQL page owner; filtering, order, cursor, and limit stay in PostgreSQL. */
@Repository
@ConditionalOnProperty(prefix = "dwp.hris.contract-compensation-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
final class JdbcHrm011QuerySource implements Hrm011QuerySource {
    private final NamedParameterJdbcTemplate jdbc;

    JdbcHrm011QuerySource(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Page read(
            long tenantId, QuerySpec query,
            HcmPopulationRepository.PopulationScope population) {
        establishTenant(tenantId);
        if (query == null || query.effectiveOn() == null || query.limit() < 1
                || query.limit() > 100 || query.sort().isEmpty()) {
            throw new IllegalArgumentException("IAQ-008 SQL page specification is invalid.");
        }
        Set<UUID> organizations = population.organizationIds().isEmpty()
                ? Set.of(new UUID(0, 0)) : population.organizationIds();
        String order = orderBy(query.sort());
        Hrm011QueryModels.MoneyFilter money = query.money();
        List<DatabaseRow> databaseRows = jdbc.query("""
                WITH candidate_rows AS (
                SELECT worker.worker_id, person.public_id AS subject_person_public_id,
                       COALESCE(assignment.business_title,person.display_name) AS title,
                       relationship.public_id AS relationship_public_id,
                       relationship.version AS relationship_version,
                       worker.public_id AS worker_public_id,
                       employer.public_id AS employer_public_id,
                       relationship.start_date AS relationship_from,
                       relationship.end_date AS relationship_to,
                       assignment.public_id AS assignment_public_id,
                       assignment.version AS assignment_version,
                       assignment.effective_start_date AS assignment_from,
                       assignment.effective_end_date AS assignment_to,
                       basis.public_id AS basis_public_id,basis.version AS basis_version,
                       basis.basis_type,basis.amount,basis.currency_code,
                       basis.frequency_code,basis.valid_from AS basis_from,
                       basis.valid_to AS basis_to
                  FROM ppl_work_relationships relationship
                  JOIN ppl_workers worker
                    ON worker.tenant_id=relationship.tenant_id
                   AND worker.worker_id=relationship.worker_id
                  JOIN ppl_persons person
                    ON person.tenant_id=worker.tenant_id
                   AND person.person_id=worker.person_id
                  JOIN ppl_legal_employers employer
                    ON employer.tenant_id=relationship.tenant_id
                   AND employer.legal_employer_id=relationship.legal_employer_id
                  LEFT JOIN ppl_assignments assignment
                    ON assignment.tenant_id=relationship.tenant_id
                   AND assignment.work_relationship_id=relationship.work_relationship_id
                   AND assignment.primary_assignment=TRUE
                   AND assignment.assignment_status='ACTIVE'
                   AND assignment.effective_start_date<=:effectiveOn
                   AND (assignment.effective_end_date IS NULL
                        OR assignment.effective_end_date>:effectiveOn)
                  LEFT JOIN ppl_organizations organization
                    ON organization.tenant_id=assignment.tenant_id
                   AND organization.organization_id=assignment.organization_id
                  LEFT JOIN ppl_compensation_basis basis
                    ON basis.tenant_id=assignment.tenant_id
                   AND basis.assignment_id=assignment.assignment_id
                   AND basis.valid_from<=:effectiveOn
                   AND (basis.valid_to IS NULL OR basis.valid_to>:effectiveOn)
                 WHERE relationship.tenant_id=:tenant
                   AND relationship.start_date<=:effectiveOn
                   AND (relationship.end_date IS NULL
                        OR relationship.end_date>:effectiveOn)
                   AND (:tenantWide OR assignment.manager_assignment_key=:managerKey
                        OR organization.public_id IN (:organizations))
                ), ranked AS (
                    SELECT candidate_rows.*,
                           count(*) OVER (
                               PARTITION BY relationship_public_id) AS candidate_count,
                           row_number() OVER (
                               PARTITION BY relationship_public_id
                               ORDER BY assignment_public_id NULLS LAST,
                                        basis_public_id NULLS LAST) AS candidate_rank
                      FROM candidate_rows
                ), one_per_relationship AS (
                    SELECT * FROM ranked WHERE candidate_rank=1
                ), filtered AS (
                    SELECT * FROM one_per_relationship
                     WHERE candidate_count<>1
                        OR assignment_public_id IS NULL OR basis_public_id IS NULL
                        OR amount IS NULL OR currency_code IS NULL
                        OR frequency_code IS NULL
                        OR ((CAST(:currency AS VARCHAR) IS NULL
                             OR currency_code=CAST(:currency AS VARCHAR))
                            AND (CAST(:minimumAmount AS NUMERIC) IS NULL
                             OR amount>=CAST(:minimumAmount AS NUMERIC))
                            AND (CAST(:maximumAmount AS NUMERIC) IS NULL
                             OR amount<=CAST(:maximumAmount AS NUMERIC)))
                ), ordered AS (
                    SELECT filtered.*,
                           row_number() OVER (ORDER BY %s) AS page_number
                      FROM filtered
                ), boundary AS (
                    SELECT page_number FROM ordered
                     WHERE relationship_public_id=CAST(:after AS UUID)
                )
                SELECT (CAST(:after AS UUID) IS NULL
                        OR EXISTS(SELECT 1 FROM boundary)) AS cursor_found,
                       page.*
                  FROM (SELECT 1) marker
                  LEFT JOIN LATERAL (
                        SELECT * FROM ordered
                         WHERE CAST(:after AS UUID) IS NULL
                            OR page_number>(SELECT page_number FROM boundary)
                         ORDER BY page_number
                         LIMIT :fetchLimit
                  ) page ON TRUE
                 ORDER BY page.page_number NULLS LAST
                """.formatted(order), new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("effectiveOn", query.effectiveOn())
                .addValue("tenantWide", population.tenantWide())
                .addValue("managerKey", population.managerAssignmentKey())
                .addValue("organizations", organizations)
                .addValue("currency", money == null ? null : money.currency())
                .addValue("minimumAmount", money == null ? null : money.minimumAmount())
                .addValue("maximumAmount", money == null ? null : money.maximumAmount())
                .addValue("after", query.afterRelationshipPublicId() == null
                        ? null : query.afterRelationshipPublicId().toString())
                .addValue("fetchLimit", query.limit() + 1),
                (result, ignored) -> new DatabaseRow(
                        result.getBoolean("cursor_found"),
                        result.getObject("relationship_public_id", UUID.class) == null
                                ? null : new Hrm011QueryModels.SourceRow(
                        result.getLong("worker_id"),
                        result.getObject("subject_person_public_id", UUID.class),
                        result.getString("title"),
                        new EmploymentContractDtos.RelationshipSnapshot(
                                result.getObject("relationship_public_id", UUID.class),
                                result.getLong("relationship_version"),
                                result.getObject("worker_public_id", UUID.class),
                                result.getObject("employer_public_id", UUID.class),
                                result.getObject("relationship_from", LocalDate.class),
                                result.getObject("relationship_to", LocalDate.class)),
                        assignment(result), compensationBasis(result),
                        result.getLong("candidate_count"))));
        if (databaseRows.isEmpty()) {
            throw new IllegalStateException("IAQ-008 SQL page returned no marker row.");
        }
        List<Hrm011QueryModels.SourceRow> rows = new ArrayList<>();
        for (DatabaseRow row : databaseRows) {
            if (row.source() != null) rows.add(row.source());
        }
        boolean hasMore = rows.size() > query.limit();
        List<Hrm011QueryModels.SourceRow> bounded = hasMore
                ? List.copyOf(rows.subList(0, query.limit())) : List.copyOf(rows);
        UUID lastScanned = bounded.isEmpty() ? null
                : bounded.getLast().relationship().relationshipPublicId();
        return new Page(
                bounded, databaseRows.getFirst().cursorFound(), hasMore, lastScanned);
    }

    private String orderBy(List<String> sorts) {
        List<String> clauses = new ArrayList<>();
        for (String sort : sorts) {
            switch (sort) {
                case "contractMetadata:ASC", "contractMetadata:DESC" -> {
                    // The exact request selects one template ID/version, so this key
                    // is constant for every candidate and does not affect ordering.
                }
                case "compensationBasis:ASC" ->
                        clauses.add("amount ASC NULLS LAST");
                case "compensationBasis:DESC" ->
                        clauses.add("amount DESC NULLS LAST");
                case "itemPublicId:ASC" -> clauses.add("relationship_public_id ASC");
                case "itemPublicId:DESC" -> clauses.add("relationship_public_id DESC");
                default -> throw new IllegalArgumentException("Unknown IAQ-008 SQL sort.");
            }
        }
        if (sorts.stream().noneMatch(value -> value.startsWith("itemPublicId:"))) {
            clauses.add("relationship_public_id ASC");
        }
        return String.join(",", clauses);
    }

    private EmploymentContractDtos.AssignmentSnapshot assignment(
            java.sql.ResultSet result) throws java.sql.SQLException {
        UUID publicId = result.getObject("assignment_public_id", UUID.class);
        LocalDate from = result.getObject("assignment_from", LocalDate.class);
        if (publicId == null || from == null) return null;
        try {
            return new EmploymentContractDtos.AssignmentSnapshot(
                    publicId, result.getLong("assignment_version"), from,
                    result.getObject("assignment_to", LocalDate.class));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private EmploymentContractDtos.CompensationBasisSnapshot compensationBasis(
            java.sql.ResultSet result) throws java.sql.SQLException {
        UUID publicId = result.getObject("basis_public_id", UUID.class);
        java.math.BigDecimal amount = result.getBigDecimal("amount");
        String basisType = result.getString("basis_type");
        String currency = result.getString("currency_code");
        String frequency = result.getString("frequency_code");
        LocalDate from = result.getObject("basis_from", LocalDate.class);
        // V59 intentionally permits incomplete staging rows. IAQ-008 must retain
        // the relationship candidate and let the projector emit a typed per-item
        // failure rather than throwing and degrading the whole page.
        if (publicId == null || amount == null || amount.signum() < 0
                || basisType == null || currency == null || frequency == null
                || from == null) return null;
        try {
            return new EmploymentContractDtos.CompensationBasisSnapshot(
                    publicId, result.getLong("basis_version"), basisType,
                    amount.signum() == 0 ? "0" : amount.stripTrailingZeros().toPlainString(),
                    currency, frequency, from,
                    result.getObject("basis_to", LocalDate.class));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void establishTenant(long tenantId) {
        String value = jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id',CAST(:tenant AS VARCHAR),TRUE)",
                new MapSqlParameterSource("tenant", tenantId), String.class);
        if (!Long.toString(tenantId).equals(value)) {
            throw new IllegalStateException("HRM-011 tenant scope was not established.");
        }
    }

    private record DatabaseRow(
            boolean cursorFound, Hrm011QueryModels.SourceRow source) {
    }
}
