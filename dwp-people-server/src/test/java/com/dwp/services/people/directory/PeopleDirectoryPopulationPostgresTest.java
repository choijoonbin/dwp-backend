package com.dwp.services.people.directory;

import com.dwp.services.people.hr.HcmPopulationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PeopleDirectoryPopulationPostgresTest {

    private static final AtomicLong TENANTS = new AtomicLong(9_180_000L);
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 17);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private static JdbcTemplate jdbc;
    private static PeopleDirectoryRepository repository;

    @BeforeAll
    static void migrateOwnerSchema() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .validateOnMigrate(true)
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new PeopleDirectoryRepository(new NamedParameterJdbcTemplate(dataSource));
    }

    @Test
    void mixedPopulationBeforeThePageDoesNotUnderfillAllowedResults() {
        long tenantId = TENANTS.incrementAndGet();
        Organization allowed = organization(tenantId, "ALLOWED");
        Organization denied = organization(tenantId, "DENIED");
        long employerId = employer(tenantId);

        Workforce actor = workforce(tenantId, employerId, allowed.id(), "ACTOR");
        workforce(tenantId, employerId, denied.id(), "OUTSIDE");
        Workforce mixed = workforce(tenantId, employerId, allowed.id(), "MIXED");
        addWorker(tenantId, mixed.personId(), employerId, denied.id(), "MIXED-OUTSIDE");
        Workforce sameWorkerMixed = workforce(
                tenantId, employerId, allowed.id(), "MIXED-SAME-WORKER");
        addRelationship(tenantId, sameWorkerMixed.workerId(), employerId, denied.id(),
                "MIXED-SAME-WORKER-OUTSIDE", false);
        Workforce allowedOne = workforce(tenantId, employerId, allowed.id(), "ALLOWED-1");
        Workforce allowedTwo = workforce(tenantId, employerId, allowed.id(), "ALLOWED-2");
        Workforce allowedThree = workforce(tenantId, employerId, allowed.id(), "ALLOWED-3");
        workforce(tenantId, employerId, allowed.id(), "ALLOWED-4");

        HcmPopulationRepository.PopulationScope population =
                new HcmPopulationRepository.PopulationScope(
                        actor.workerId(), null, false, Set.of(allowed.publicId()),
                        Set.of("DIRECTORY", "EMPLOYMENT"), "policy-v3");

        List<PeopleDirectoryRepository.DirectoryRow> rows =
                repository.searchWithinPopulation(
                        tenantId, 0L, null, "ACTIVE", AS_OF, 3,
                        false, false, population);

        assertThat(rows)
                .extracting(PeopleDirectoryRepository.DirectoryRow::publicId)
                .containsExactly(
                        allowedOne.publicId(), allowedTwo.publicId(), allowedThree.publicId());
    }

    private Organization organization(long tenantId, String suffix) {
        jdbc.update("""
                INSERT INTO ppl_organization_type_catalog(
                    tenant_id, type_key, display_name)
                VALUES (?, 'DEPARTMENT', 'Department')
                ON CONFLICT (tenant_id, type_key) DO NOTHING
                """, tenantId);
        UUID publicId = UUID.randomUUID();
        long id = jdbc.queryForObject("""
                INSERT INTO ppl_organizations(
                    tenant_id, public_id, organization_key, organization_type, name)
                VALUES (?, ?, ?, 'DEPARTMENT', ?)
                RETURNING organization_id
                """, Long.class, tenantId, publicId,
                "ORG-" + suffix + '-' + publicId, "Organization " + suffix);
        return new Organization(id, publicId);
    }

    private long employer(long tenantId) {
        UUID key = UUID.randomUUID();
        return jdbc.queryForObject("""
                INSERT INTO ppl_legal_employers(tenant_id, employer_key, legal_name)
                VALUES (?, ?, 'Synthetic Legal Employer')
                RETURNING legal_employer_id
                """, Long.class, tenantId, "EMPLOYER-" + key);
    }

    private Workforce workforce(
            long tenantId,
            long employerId,
            long organizationId,
            String suffix) {
        UUID publicId = UUID.randomUUID();
        long personId = jdbc.queryForObject("""
                INSERT INTO ppl_persons(
                    tenant_id, public_id, person_key, display_name, lifecycle_state)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                RETURNING person_id
                """, Long.class, tenantId, publicId,
                "PERSON-" + suffix + '-' + publicId, "Person " + suffix);
        long workerId = addWorker(
                tenantId, personId, employerId, organizationId, suffix);
        return new Workforce(personId, publicId, workerId);
    }

    private long addWorker(
            long tenantId,
            long personId,
            long employerId,
            long organizationId,
            String suffix) {
        UUID key = UUID.randomUUID();
        long workerId = jdbc.queryForObject("""
                INSERT INTO ppl_workers(
                    tenant_id, person_id, worker_number, worker_type, worker_status,
                    original_hire_date)
                VALUES (?, ?, ?, 'EMPLOYEE', 'ACTIVE', DATE '2020-01-01')
                RETURNING worker_id
                """, Long.class, tenantId, personId, "WORKER-" + suffix + '-' + key);
        addRelationship(tenantId, workerId, employerId, organizationId, suffix, true);
        return workerId;
    }

    private void addRelationship(
            long tenantId,
            long workerId,
            long employerId,
            long organizationId,
            String suffix,
            boolean primary) {
        UUID key = UUID.randomUUID();
        long relationshipId = jdbc.queryForObject("""
                INSERT INTO ppl_work_relationships(
                    tenant_id, relationship_key, worker_id, legal_employer_id,
                    relationship_type, primary_relationship, start_date)
                VALUES (?, ?, ?, ?, 'EMPLOYEE', ?, DATE '2020-01-01')
                RETURNING work_relationship_id
                """, Long.class, tenantId, "RELATIONSHIP-" + suffix + '-' + key,
                workerId, employerId, primary);
        jdbc.update("""
                INSERT INTO ppl_assignments(
                    tenant_id, assignment_key, work_relationship_id,
                    effective_start_date, assignment_status, primary_assignment,
                    organization_id, business_title)
                VALUES (?, ?, ?, DATE '2020-01-01', 'ACTIVE', ?, ?, 'Engineer')
                """, tenantId, "ASSIGNMENT-" + suffix + '-' + key,
                relationshipId, primary, organizationId);
    }

    private record Organization(long id, UUID publicId) {
    }

    private record Workforce(long personId, UUID publicId, long workerId) {
    }
}
