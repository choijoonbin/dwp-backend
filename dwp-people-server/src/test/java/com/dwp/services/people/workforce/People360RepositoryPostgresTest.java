package com.dwp.services.people.workforce;

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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class People360RepositoryPostgresTest {

    private static final AtomicLong TENANTS = new AtomicLong(9_170_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private static JdbcTemplate jdbc;
    private static People360Repository repository;

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
        repository = new People360Repository(new NamedParameterJdbcTemplate(dataSource));
    }

    @Test
    void assignmentAndRelationshipBoundariesAreInclusiveOnlyInsideTheAsOfWindow() {
        Fixture fixture = fixture(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assignment(
                fixture, "SYN-ASG-BOUNDARY",
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 9, 30));

        assertThat(rows(fixture, LocalDate.of(2025, 12, 31))).isEmpty();
        assertThat(primaryCount(fixture, LocalDate.of(2026, 2, 28))).isZero();
        assertThat(primaryCount(fixture, LocalDate.of(2026, 3, 1))).isEqualTo(1);
        assertThat(primaryCount(fixture, LocalDate.of(2026, 9, 30))).isEqualTo(1);
        assertThat(primaryCount(fixture, LocalDate.of(2026, 10, 1))).isZero();
        assertThat(rows(fixture, LocalDate.of(2027, 1, 1))).isEmpty();
    }

    @Test
    void overlappingPrimarySlicesRemainVisibleAsTwoRowsForServiceFailClosedHandling() {
        Fixture fixture = fixture(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assignment(
                fixture, "SYN-ASG-ONE",
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 9, 30));
        assignment(
                fixture, "SYN-ASG-TWO",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 8, 31));

        List<People360Repository.CurrentEmploymentRow> rows =
                rows(fixture, LocalDate.of(2026, 6, 1));

        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(row -> row.assignmentId() != null);
        assertThat(rows).extracting(People360Repository.CurrentEmploymentRow::assignmentKey)
                .containsExactlyInAnyOrder("SYN-ASG-ONE", "SYN-ASG-TWO");
    }

    private Fixture fixture(LocalDate relationshipStart, LocalDate relationshipEnd) {
        long tenantId = TENANTS.incrementAndGet();
        UUID personPublicId = UUID.randomUUID();
        long personId = jdbc.queryForObject("""
                INSERT INTO ppl_persons(
                    tenant_id, public_id, person_key, display_name,
                    preferred_locale, time_zone, lifecycle_state)
                VALUES (?, ?, ?, 'Synthetic Person', 'en', 'UTC', 'ACTIVE')
                RETURNING person_id
                """, Long.class, tenantId, personPublicId,
                "synthetic-person-" + personPublicId);
        long workerId = jdbc.queryForObject("""
                INSERT INTO ppl_workers(
                    tenant_id, person_id, worker_number, worker_type, worker_status,
                    original_hire_date)
                VALUES (?, ?, ?, 'EMPLOYEE', 'ACTIVE', DATE '2026-01-01')
                RETURNING worker_id
                """, Long.class, tenantId, personId, "SYN-" + personPublicId);
        long employerId = jdbc.queryForObject("""
                INSERT INTO ppl_legal_employers(tenant_id, employer_key, legal_name)
                VALUES (?, ?, 'Synthetic Legal Employer')
                RETURNING legal_employer_id
                """, Long.class, tenantId, "SYN-EMP-" + personPublicId);
        long relationshipId = jdbc.queryForObject("""
                INSERT INTO ppl_work_relationships(
                    tenant_id, relationship_key, worker_id, legal_employer_id,
                    relationship_type, primary_relationship, start_date, end_date)
                VALUES (?, ?, ?, ?, 'EMPLOYEE', TRUE, ?, ?)
                RETURNING work_relationship_id
                """, Long.class, tenantId, "SYN-REL-" + personPublicId,
                workerId, employerId, relationshipStart, relationshipEnd);
        return new Fixture(tenantId, personId, relationshipId);
    }

    private void assignment(
            Fixture fixture,
            String assignmentKey,
            LocalDate start,
            LocalDate end) {
        jdbc.update("""
                INSERT INTO ppl_assignments(
                    tenant_id, assignment_key, work_relationship_id,
                    effective_start_date, effective_end_date, effective_sequence,
                    assignment_status, primary_assignment, business_title)
                VALUES (?, ?, ?, ?, ?, 1, 'ACTIVE', TRUE, 'Synthetic Engineer')
                """, fixture.tenantId(), assignmentKey,
                fixture.relationshipId(), start, end);
    }

    private List<People360Repository.CurrentEmploymentRow> rows(
            Fixture fixture,
            LocalDate asOf) {
        return repository.findCurrentEmployments(
                fixture.tenantId(), fixture.personId(), asOf);
    }

    private long primaryCount(Fixture fixture, LocalDate asOf) {
        return rows(fixture, asOf).stream()
                .filter(row -> row.assignmentId() != null)
                .count();
    }

    private record Fixture(long tenantId, long personId, long relationshipId) {
    }
}
