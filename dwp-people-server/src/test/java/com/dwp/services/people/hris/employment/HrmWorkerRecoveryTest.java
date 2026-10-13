package com.dwp.services.people.hris.employment;

import com.dwp.services.people.hr.assignment.AssignmentProposalCanonicalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class HrmWorkerRecoveryTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final UUID CORRELATION = UUID.fromString(
            "00000000-0000-4000-8000-000000000051");
    private static JdbcTemplate jdbc;
    private static EmploymentEventRepository repository;
    private static EmploymentEventCanonicalizer canonicalizer;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new EmploymentEventRepository(
                new NamedParameterJdbcTemplate(dataSource), MAPPER);
        canonicalizer = new EmploymentEventCanonicalizer(
                MAPPER, new AssignmentProposalCanonicalizer(MAPPER));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void failedPublicationHasNoAssignmentEventAndSuccessfulCommitIsRecoverable() {
        Seed seed = seed();
        EmploymentEventDtos.NormalizedCommand command = command(seed);
        AtomicReference<EmploymentEventRepository.EventRow> approved = new AtomicReference<>();

        transactions.executeWithoutResult(ignored -> {
            EmploymentEventRepository.PreflightSnapshot snapshot =
                    repository.preflight(seed.tenantId(), command, true);
            EmploymentEventRepository.EventRow draft = repository.createEvent(
                    seed.tenantId(), command, snapshot,
                    canonicalizer.digest(command.payload()), CORRELATION, 17L);
            EmploymentEventRepository.EventRow validated = repository.validate(
                    seed.tenantId(), draft.publicId(), draft.version(),
                    "a".repeat(64), "{}", 17L);
            EmploymentEventRepository.EventRow submitted = repository.submit(
                    seed.tenantId(), validated.publicId(), validated.version(),
                    UUID.randomUUID(), 17L);
            EmploymentEventDtos.ApprovalDecision decision =
                    new EmploymentEventDtos.ApprovalDecision(
                            submitted.approvalRequestId(), UUID.randomUUID(), "APPROVED",
                            Instant.parse("2026-10-13T04:00:00Z"), UUID.randomUUID(),
                            1, submitted.version(), CORRELATION, "b".repeat(64));
            repository.recordApproval(seed.tenantId(), submitted, decision);
            approved.set(repository.applyApproval(seed.tenantId(), submitted, decision));
        });

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            EmploymentEventRepository.PreflightSnapshot current =
                    repository.preflight(seed.tenantId(), command, true);
            repository.publishLedger(
                    seed.tenantId(), approved.get(), command, current, 29L, CORRELATION);
            throw new ForcedPostLedgerFailure();
        })).isInstanceOf(ForcedPostLedgerFailure.class);

        assertThat(countAssignmentChanged(approved.get().publicId())).isZero();
        assertThat(countLedgerAssignment(approved.get().publicId())).isZero();
        assertThat(lifecycle(approved.get().publicId())).isEqualTo("APPROVED");

        transactions.executeWithoutResult(ignored -> {
            EmploymentEventRepository.EventRow current = repository.lockEvent(
                    seed.tenantId(), approved.get().publicId()).orElseThrow();
            EmploymentEventRepository.PreflightSnapshot snapshot =
                    repository.preflight(seed.tenantId(), command, true);
            repository.publishLedger(
                    seed.tenantId(), current, command, snapshot, 29L, CORRELATION);
        });

        assertThat(countLedgerAssignment(approved.get().publicId())).isEqualTo(1);
        assertThat(countAssignmentChanged(approved.get().publicId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_people_outbox_events
                 WHERE aggregate_type='EMPLOYMENT_EVENT' AND aggregate_id=?
                   AND event_type IN ('WorkerChanged.v1','EmploymentChanged.v1',
                                      'AssignmentChanged.v1',
                                      'WorkplaceAssignmentChanged.v1')
                """, Integer.class, approved.get().publicId().toString())).isEqualTo(4);
        assertThat(lifecycle(approved.get().publicId())).isEqualTo("PUBLISHED");

        jdbc.update("""
                UPDATE sys_people_outbox_events SET attempt_count=attempt_count+1
                 WHERE aggregate_type='EMPLOYMENT_EVENT' AND aggregate_id=?
                """, approved.get().publicId().toString());
        assertThat(countLedgerAssignment(approved.get().publicId())).isEqualTo(1);
        assertThat(lifecycle(approved.get().publicId())).isEqualTo("PUBLISHED");
    }

    private static Seed seed() {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT person.tenant_id,person.public_id AS person_public_id,
                       employer.public_id AS employer_public_id,
                       employer.version AS employer_version,
                       organization.public_id AS organization_public_id,
                       organization.version AS organization_version,
                       position.public_id AS position_public_id,
                       position.version AS position_version,
                       job.public_id AS job_public_id,job.version AS job_version,
                       location.public_id AS location_public_id,
                       location.version AS location_version
                  FROM ppl_persons person
                  JOIN ppl_positions position ON position.tenant_id=person.tenant_id
                  JOIN ppl_organizations organization
                    ON organization.tenant_id=position.tenant_id
                   AND organization.organization_id=position.organization_id
                  JOIN ppl_job_profiles job ON job.tenant_id=position.tenant_id
                   AND job.job_profile_id=position.job_profile_id
                  JOIN ppl_locations location ON location.tenant_id=position.tenant_id
                   AND location.location_id=position.location_id
                  JOIN ppl_legal_employers employer
                    ON employer.tenant_id=person.tenant_id
                 WHERE person.lifecycle_state='ACTIVE'
                   AND organization.lifecycle_state='ACTIVE'
                   AND job.lifecycle_state='ACTIVE'
                   AND location.lifecycle_state='ACTIVE'
                   AND employer.lifecycle_state='ACTIVE'
                 ORDER BY person.person_id,position.position_id,employer.legal_employer_id
                 LIMIT 1
                """);
        jdbc.update("""
                UPDATE ppl_locations SET time_zone=COALESCE(time_zone,'Etc/UTC')
                 WHERE tenant_id=? AND public_id=?
                """, row.get("tenant_id"), row.get("location_public_id"));
        return new Seed(
                number(row, "tenant_id"), uuid(row, "person_public_id"),
                ref(row, "employer", "HRM.LegalEmployer.v1"),
                ref(row, "organization", "HRM.Organization.v1"),
                ref(row, "position", "HRM.Position.v1"),
                ref(row, "job", "HRM.Job.v1"),
                ref(row, "location", "HRM.WorkLocation.v1"));
    }

    private static EmploymentEventDtos.OwnerRef ref(
            Map<String, Object> row, String prefix, String schema) {
        UUID id = uuid(row, prefix + "_public_id");
        long revision = number(row, prefix + "_version");
        return new EmploymentEventDtos.OwnerRef(
                "HRM", schema, id, revision,
                canonicalizer.ownerRefDigest(schema, id, revision), null, null);
    }

    private static EmploymentEventDtos.NormalizedCommand command(Seed seed) {
        LocalDate start = LocalDate.of(2026, 10, 13);
        EmploymentEventDtos.AssignmentDraft assignment =
                new EmploymentEventDtos.AssignmentDraft(
                        seed.organization(), seed.position(), seed.job(), seed.location(),
                        start, null, true, BigDecimal.ONE);
        EmploymentEventDtos.HirePayload payload = new EmploymentEventDtos.HirePayload(
                seed.personPublicId(), seed.employer(), "EMPLOYEE", start,
                "CREATE_NEW", assignment);
        ObjectNode body = MAPPER.valueToTree(payload);
        ((ObjectNode) body.path("assignment")).put("fte", "1.0");
        EmploymentEventDtos.CreateCommand create = new EmploymentEventDtos.CreateCommand(
                new EmploymentEventDtos.CommandContext(
                        0, "hrm-worker-recovery-0001", EmploymentEventDtos.PURPOSE,
                        start.atStartOfDay().toInstant(ZoneOffset.UTC)),
                "HIRE", body);
        return canonicalizer.normalize(create);
    }

    private static int countAssignmentChanged(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_people_outbox_events
                 WHERE aggregate_type='EMPLOYMENT_EVENT' AND aggregate_id=?
                   AND event_type='AssignmentChanged.v1'
                """, Integer.class, eventId.toString());
    }

    private static int countLedgerAssignment(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM ppl_assignments
                 WHERE assignment_key=?
                """, Integer.class, "HRM-ASG-" + eventId.toString().replace("-", ""));
    }

    private static String lifecycle(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_assignment_events WHERE public_id=?
                """, String.class, eventId);
    }

    private static UUID uuid(Map<String, Object> row, String key) {
        return (UUID) row.get(key);
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private record Seed(
            long tenantId,
            UUID personPublicId,
            EmploymentEventDtos.OwnerRef employer,
            EmploymentEventDtos.OwnerRef organization,
            EmploymentEventDtos.OwnerRef position,
            EmploymentEventDtos.OwnerRef job,
            EmploymentEventDtos.OwnerRef location) { }

    private static final class ForcedPostLedgerFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
