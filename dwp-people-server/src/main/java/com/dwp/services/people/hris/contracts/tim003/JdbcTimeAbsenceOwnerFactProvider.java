package com.dwp.services.people.hris.contracts.tim003;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repeatable-read, exact-relationship current-business-date People owner provider. */
@Component
final class JdbcTimeAbsenceOwnerFactProvider
        implements TimeAbsenceOwnerPorts.OwnerFactProvider {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate repeatableRead;
    private final Clock clock;

    @Autowired
    JdbcTimeAbsenceOwnerFactProvider(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactions) {
        this(jdbc, transactions, Clock.systemUTC());
    }

    JdbcTimeAbsenceOwnerFactProvider(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.repeatableRead = new TransactionTemplate(transactions);
        this.repeatableRead.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.repeatableRead.setIsolationLevel(
                TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.repeatableRead.setReadOnly(true);
        this.repeatableRead.setTimeout(10);
    }

    @Override
    public Optional<TimeAbsenceOwnerContract.OwnerFacts> readCurrent(
            TimeAbsenceOwnerContract.VerifiedRequest request,
            TimeAbsenceOwnerContract.OwnerGrant grant) {
        Optional<TimeAbsenceOwnerContract.OwnerFacts> value = repeatableRead.execute(
                status -> readSnapshot(request, grant));
        return value == null ? Optional.empty() : value;
    }

    private Optional<TimeAbsenceOwnerContract.OwnerFacts> readSnapshot(
            TimeAbsenceOwnerContract.VerifiedRequest request,
            TimeAbsenceOwnerContract.OwnerGrant grant) {
        if (!grant.exactlyMatches(request)) {
            throw TimeAbsenceOwnerContract.Failure.authorityStale();
        }
        Instant asOf = clock.instant().truncatedTo(ChronoUnit.MICROS);
        MapSqlParameterSource tenantParameters = new MapSqlParameterSource()
                .addValue("tenant", request.tenantId());
        String appliedTenant = jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', CAST(:tenant AS VARCHAR), TRUE)",
                tenantParameters, String.class);
        if (!Long.toString(request.tenantId()).equals(appliedTenant)) {
            throw TimeAbsenceOwnerContract.Failure.authorityStale();
        }
        String configuredZone = jdbc.query("""
                SELECT configuration ->> 'hrisBusinessTimeZone' AS business_time_zone
                  FROM sys_service_tenants
                 WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE'
                """, tenantParameters, result ->
                result.next() ? result.getString("business_time_zone") : null);
        try {
            TimeAbsenceOwnerContract.canonicalZone(
                    configuredZone, "hrisBusinessTimeZone");
        } catch (RuntimeException exception) {
            throw TimeAbsenceOwnerContract.Failure.configurationInvalid();
        }
        if (!grant.businessTimeZone().equals(configuredZone)) {
            throw TimeAbsenceOwnerContract.Failure.authorityStale();
        }
        LocalDate currentBusinessDate = LocalDate.ofInstant(
                asOf, ZoneId.of(configuredZone));
        if (!currentBusinessDate.equals(request.request().effectiveOn())) {
            throw TimeAbsenceOwnerContract.Failure.dateRolled();
        }

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenant", request.tenantId())
                .addValue("relationship", request.request().relationshipPublicId())
                .addValue("effectiveOn", request.request().effectiveOn());
        List<RelationshipRow> relationships = jdbc.query("""
                SELECT relationship.public_id AS relationship_public_id,
                       relationship.version AS relationship_revision,
                       relationship.relationship_type,
                       relationship.start_date,
                       relationship.end_date,
                       worker.public_id AS worker_public_id,
                       worker.worker_status,
                       employer.public_id AS legal_entity_public_id,
                       relationship.work_relationship_id
                  FROM ppl_work_relationships relationship
                  JOIN ppl_workers worker
                    ON worker.tenant_id=relationship.tenant_id
                   AND worker.worker_id=relationship.worker_id
                  JOIN ppl_legal_employers employer
                    ON employer.tenant_id=relationship.tenant_id
                   AND employer.legal_employer_id=relationship.legal_employer_id
                 WHERE relationship.tenant_id=:tenant
                   AND relationship.public_id=:relationship
                """, parameters, (result, rowNumber) -> new RelationshipRow(
                result.getObject("relationship_public_id", UUID.class),
                result.getLong("relationship_revision"),
                result.getString("relationship_type"),
                result.getObject("start_date", LocalDate.class),
                result.getObject("end_date", LocalDate.class),
                result.getObject("worker_public_id", UUID.class),
                result.getString("worker_status"),
                result.getObject("legal_entity_public_id", UUID.class),
                result.getLong("work_relationship_id")));
        if (relationships.isEmpty()) return Optional.empty();
        if (relationships.size() != 1) {
            throw TimeAbsenceOwnerContract.Failure.contractInvalid();
        }
        RelationshipRow relationship = relationships.getFirst();
        if (relationship.relationshipRevision() < 0) {
            throw TimeAbsenceOwnerContract.Failure.contractInvalid();
        }

        parameters.addValue("relationshipId", relationship.internalId());
        List<TimeAbsenceOwnerContract.AssignmentFact> assignments =
                new ArrayList<>(jdbc.query("""
                        SELECT assignment.public_id AS assignment_public_id,
                               assignment.version AS assignment_revision,
                               assignment.assignment_status,
                               employer.public_id AS legal_entity_public_id,
                               organization.public_id AS organization_public_id,
                               location.public_id AS location_public_id,
                               location.time_zone,
                               assignment.effective_start_date,
                               assignment.effective_end_date
                          FROM ppl_assignments assignment
                          JOIN ppl_work_relationships relationship
                            ON relationship.tenant_id=assignment.tenant_id
                           AND relationship.work_relationship_id=
                               assignment.work_relationship_id
                          JOIN ppl_legal_employers employer
                            ON employer.tenant_id=relationship.tenant_id
                           AND employer.legal_employer_id=
                               relationship.legal_employer_id
                          LEFT JOIN ppl_organizations organization
                            ON organization.tenant_id=assignment.tenant_id
                           AND organization.organization_id=assignment.organization_id
                          LEFT JOIN ppl_locations location
                            ON location.tenant_id=assignment.tenant_id
                           AND location.location_id=assignment.location_id
                         WHERE assignment.tenant_id=:tenant
                           AND assignment.work_relationship_id=:relationshipId
                           AND assignment.effective_start_date<=:effectiveOn
                           AND (assignment.effective_end_date IS NULL
                                OR assignment.effective_end_date>=:effectiveOn)
                        """, parameters, (result, rowNumber) ->
                        new TimeAbsenceOwnerContract.AssignmentFact(
                                result.getObject("assignment_public_id", UUID.class),
                                result.getLong("assignment_revision"),
                                result.getString("assignment_status"),
                                result.getObject("legal_entity_public_id", UUID.class),
                                result.getObject("organization_public_id", UUID.class),
                                result.getObject("location_public_id", UUID.class),
                                result.getString("time_zone"),
                                result.getObject("effective_start_date", LocalDate.class),
                                exclusive(result.getObject(
                                        "effective_end_date", LocalDate.class)))));
        assignments.sort(Comparator.comparing(
                assignment -> assignment.assignmentPublicId().toString()));

        LocalDate relationshipEffectiveTo = exclusive(relationship.endDate());
        String relationshipStatus = relationshipStatus(
                relationship.workerStatus(), request.request().effectiveOn(),
                relationship.startDate(), relationshipEffectiveTo);
        return Optional.of(new TimeAbsenceOwnerContract.OwnerFacts(
                relationship.publicId(), relationship.relationshipRevision(),
                relationship.workerPublicId(), relationship.workerStatus(),
                relationship.relationshipType(), relationshipStatus,
                relationship.legalEntityPublicId(), relationship.startDate(),
                relationshipEffectiveTo, assignments, asOf));
    }

    private static LocalDate exclusive(LocalDate inclusive) {
        if (inclusive == null) return null;
        try {
            return inclusive.plusDays(1);
        } catch (DateTimeException exception) {
            throw TimeAbsenceOwnerContract.Failure.contractInvalid();
        }
    }

    private static String relationshipStatus(
            String workerStatus,
            LocalDate effectiveOn,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        if ("TERMINATED".equals(workerStatus)) return "TERMINATED";
        if ("PENDING".equals(workerStatus) || effectiveOn.isBefore(effectiveFrom)) {
            return "PENDING";
        }
        if (effectiveTo != null && !effectiveOn.isBefore(effectiveTo)) return "INACTIVE";
        return "ACTIVE";
    }

    private record RelationshipRow(
            UUID publicId,
            long relationshipRevision,
            String relationshipType,
            LocalDate startDate,
            LocalDate endDate,
            UUID workerPublicId,
            String workerStatus,
            UUID legalEntityPublicId,
            long internalId) {
    }
}
