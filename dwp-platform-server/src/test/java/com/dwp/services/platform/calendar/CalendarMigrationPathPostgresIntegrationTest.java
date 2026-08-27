package com.dwp.services.platform.calendar;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class CalendarMigrationPathPostgresIntegrationTest {

    private static final int APPLIED_V192_CHECKSUM = -1_640_096_361;

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void freshV1ToLatestKeepsTheAppliedCalendarMigrationImmutable() {
        String schema = "calendar_fresh_path";
        Flyway latest = flyway(schema, null);
        latest.clean();
        latest.migrate();

        assertMigrationPath(schema);
        assertLatestSharingGovernance(schema);
    }

    @Test
    void v191ToLatestAppliesCalendarConvergenceForwardOnly() {
        String schema = "calendar_upgrade_path";
        Flyway throughV191 = flyway(schema, "191");
        throughV191.clean();
        throughV191.migrate();

        flyway(schema, "192").migrate();
        assertThat(activeTenantGrants(schema, "TEAM")).isPositive();

        flyway(schema, null).migrate();

        assertMigrationPath(schema);
        assertLatestSharingGovernance(schema);
    }

    private void assertMigrationPath(String schema) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        assertThat(jdbc.queryForObject("""
                SELECT checksum
                  FROM %s.flyway_schema_history
                 WHERE version = '192' AND success
                """.formatted(schema), Integer.class)).isEqualTo(APPLIED_V192_CHECKSUM);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM %s.flyway_schema_history
                 WHERE version = '196' AND success
                """.formatted(schema), Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.tables
                 WHERE table_schema = ?
                   AND table_name IN (
                       'cal_calendar_access_grants',
                       'cal_calendar_subscriptions',
                       'cal_event_user_preferences')
                """, Integer.class, schema)).isEqualTo(3);
    }

    private void assertLatestSharingGovernance(String schema) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        assertThat(activeTenantGrants(schema, "TEAM")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM %1$s.cal_calendar_access_grants grant_row
                  JOIN %1$s.cal_calendars calendar
                    ON calendar.tenant_id = grant_row.tenant_id
                   AND calendar.calendar_id = grant_row.calendar_id
                 WHERE calendar.calendar_type = 'TEAM'
                   AND grant_row.principal_type = 'TENANT'
                   AND grant_row.lifecycle_state = 'REVOKED'
                """.formatted(schema), Integer.class)).isPositive();
        assertThat(activeTenantGrants(schema, "SYSTEM")).isEqualTo(
                jdbc.queryForObject("""
                        SELECT COUNT(*) FROM %s.cal_calendars
                         WHERE calendar_type = 'SYSTEM' AND lifecycle_state = 'ACTIVE'
                        """.formatted(schema), Integer.class));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM %1$s.cal_calendar_access_grants grant_row
                  JOIN %1$s.cal_calendars calendar
                    ON calendar.tenant_id = grant_row.tenant_id
                   AND calendar.calendar_id = grant_row.calendar_id
                 WHERE calendar.calendar_type = 'SYSTEM'
                   AND grant_row.principal_type = 'TENANT'
                   AND grant_row.lifecycle_state = 'ACTIVE'
                   AND grant_row.access_level <> 'VIEW_DETAILS'
                """.formatted(schema), Integer.class)).isZero();
    }

    private int activeTenantGrants(String schema, String calendarType) {
        return new JdbcTemplate(dataSource()).queryForObject("""
                SELECT COUNT(*)
                  FROM %1$s.cal_calendar_access_grants grant_row
                  JOIN %1$s.cal_calendars calendar
                    ON calendar.tenant_id = grant_row.tenant_id
                   AND calendar.calendar_id = grant_row.calendar_id
                 WHERE calendar.calendar_type = ?
                   AND grant_row.principal_type = 'TENANT'
                   AND grant_row.lifecycle_state = 'ACTIVE'
                """.formatted(schema), Integer.class, calendarType);
    }

    private Flyway flyway(String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource())
                .schemas(schema)
                .defaultSchema(schema)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }
}
