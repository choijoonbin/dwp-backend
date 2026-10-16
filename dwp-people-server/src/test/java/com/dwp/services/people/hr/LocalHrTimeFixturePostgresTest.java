package com.dwp.services.people.hr;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class LocalHrTimeFixturePostgresTest {

    private static final LocalDate AS_OF = LocalDate.of(2037, 3, 18);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private static JdbcTemplate jdbc;
    private static LocalHrTimeFixture fixture;

    @BeforeAll
    static void migrateAndWireFixture() {
        DataSource dataSource = dataSource();
        Flyway.configure(LocalHrTimeFixturePostgresTest.class.getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .defaultSchema("public")
                .schemas("public")
                .table("flyway_schema_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .validateOnMigrate(true)
                .outOfOrder(false)
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        fixture = new LocalHrTimeFixture(jdbc, "local");
    }

    @Test
    void rollsForwardIdempotentlyWithoutOverwritingUserOwnedTimeData() {
        Worker worker = activeWorker();
        LocalDate weekStart = AS_OF.with(
                TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        LocalHrTimeFixture.RefreshResult first = fixture.refresh(AS_OF);

        assertThat(first.insertedCards()).isPositive();
        assertThat(first.insertedEntries()).isPositive();
        assertThat(countFixtureCards(worker, weekStart)).isEqualTo(2);
        assertThat(countEntries(worker, weekStart)).isEqualTo(3);
        assertThat(countEntries(worker, weekStart.minusWeeks(1))).isEqualTo(5);

        LocalHrTimeFixture.RefreshResult replay = fixture.refresh(AS_OF);

        assertThat(replay.insertedCards()).isZero();
        assertThat(replay.insertedEntries()).isZero();
        assertThat(replay.insertedExceptions()).isZero();
        assertThat(replay.refreshedTotals()).isZero();

        LocalHrTimeFixture.RefreshResult nextDay = fixture.refresh(AS_OF.plusDays(1));

        assertThat(nextDay.insertedCards()).isZero();
        assertThat(nextDay.insertedEntries()).isPositive();
        assertThat(countEntries(worker, weekStart)).isEqualTo(4);
        assertThat(fixture.refresh(AS_OF.plusDays(1)).insertedEntries()).isZero();

        Map<String, Object> entry = jdbc.queryForMap("""
                SELECT entry.time_entry_id, card.time_card_id
                  FROM tme_time_cards card
                  JOIN tme_time_entries entry
                    ON entry.tenant_id = card.tenant_id
                   AND entry.time_card_id = card.time_card_id
                 WHERE card.tenant_id = ?
                   AND card.worker_id = ?
                   AND card.period_start_date = ?
                 ORDER BY entry.work_date
                 LIMIT 1
                """, worker.tenantId(), worker.workerId(), weekStart);
        long entryId = ((Number) entry.get("time_entry_id")).longValue();
        long cardId = ((Number) entry.get("time_card_id")).longValue();
        jdbc.update("""
                UPDATE tme_time_entries
                   SET minutes = 137,
                       note = 'user-owned-entry',
                       source_reference = 'self-service',
                       version = version + 1
                 WHERE time_entry_id = ?
                """, entryId);
        jdbc.update("""
                UPDATE tme_time_cards
                   SET status = 'SUBMITTED', version = version + 1
                 WHERE time_card_id = ?
                """, cardId);

        fixture.refresh(AS_OF);

        assertThat(jdbc.queryForMap("""
                SELECT minutes, note, source_reference, version
                  FROM tme_time_entries
                 WHERE time_entry_id = ?
                """, entryId))
                .containsEntry("minutes", 137)
                .containsEntry("note", "user-owned-entry")
                .containsEntry("source_reference", "self-service")
                .containsEntry("version", 1L);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM tme_time_cards WHERE time_card_id = ?
                """, String.class, cardId)).isEqualTo("SUBMITTED");
    }

    @Test
    void leavesAUserCreatedCardUntouchedInsteadOfAttachingFixtureRows() {
        Worker worker = activeWorker();
        LocalDate asOf = AS_OF.plusWeeks(4);
        LocalDate weekStart = asOf.with(
                TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        UUID userCardId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tme_time_cards (
                    public_id, tenant_id, worker_id,
                    period_start_date, period_end_date, status,
                    scheduled_minutes, recorded_minutes, exception_count,
                    data_origin, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'OPEN', 2100, 0, 0, 'MANUAL', 77, 77)
                """, userCardId, worker.tenantId(), worker.workerId(),
                weekStart, weekStart.plusDays(6));

        fixture.refresh(asOf);

        Map<String, Object> card = jdbc.queryForMap("""
                SELECT public_id, status, scheduled_minutes, data_origin,
                       created_by, updated_by
                  FROM tme_time_cards
                 WHERE tenant_id = ? AND worker_id = ? AND period_start_date = ?
                """, worker.tenantId(), worker.workerId(), weekStart);
        assertThat(card)
                .containsEntry("public_id", userCardId)
                .containsEntry("status", "OPEN")
                .containsEntry("scheduled_minutes", 2100)
                .containsEntry("data_origin", "MANUAL")
                .containsEntry("created_by", 77L)
                .containsEntry("updated_by", 77L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM tme_time_entries entry
                  JOIN tme_time_cards time_card
                    ON time_card.tenant_id = entry.tenant_id
                   AND time_card.time_card_id = entry.time_card_id
                 WHERE time_card.public_id = ?
                """, Long.class, userCardId)).isZero();
    }

    @Test
    void rejectsConstructionForEveryNonLocalRuntime() {
        assertThatThrownBy(() -> new LocalHrTimeFixture(jdbc, "production"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forbidden outside the local runtime environment");
        assertThatThrownBy(() -> new LocalHrTimeFixture(jdbc, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forbidden outside the local runtime environment");
    }

    private static Worker activeWorker() {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT worker.tenant_id, worker.worker_id
                  FROM sys_service_tenants tenant
                  JOIN ppl_workers worker ON worker.tenant_id = tenant.tenant_id
                 WHERE tenant.tenant_key = 'default'
                   AND tenant.lifecycle_state = 'ACTIVE'
                   AND worker.worker_status = 'ACTIVE'
                 ORDER BY worker.worker_id
                 LIMIT 1
                """);
        return new Worker(
                ((Number) row.get("tenant_id")).longValue(),
                ((Number) row.get("worker_id")).longValue());
    }

    private static long countFixtureCards(Worker worker, LocalDate weekStart) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM tme_time_cards
                 WHERE tenant_id = ? AND worker_id = ?
                   AND period_start_date IN (?, ?)
                   AND data_origin = 'REFERENCE'
                """, Long.class, worker.tenantId(), worker.workerId(),
                weekStart, weekStart.minusWeeks(1));
    }

    private static long countEntries(Worker worker, LocalDate weekStart) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM tme_time_entries entry
                  JOIN tme_time_cards card
                    ON card.tenant_id = entry.tenant_id
                   AND card.time_card_id = entry.time_card_id
                 WHERE card.tenant_id = ? AND card.worker_id = ?
                   AND card.period_start_date = ?
                   AND entry.lifecycle_state = 'ACTIVE'
                """, Long.class, worker.tenantId(), worker.workerId(), weekStart);
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private record Worker(long tenantId, long workerId) {
    }
}
