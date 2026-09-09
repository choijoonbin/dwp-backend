package com.dwp.services.meeting.videomeeting.domain;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MeetingEntryFenceMigrationPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void migrateToPreviousVersion() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway baseline = flyway("39");
        baseline.clean();
        baseline.migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void upgradePreservesUnsafeHistoryButFencesEveryNewEntryConfiguration() {
        UUID historical = jdbc.queryForObject("""
                SELECT meeting_id FROM vm_meetings
                 WHERE lifecycle_state IN ('SCHEDULED', 'LOBBY')
                 ORDER BY meeting_id LIMIT 1
                """, UUID.class);
        jdbc.update("""
                UPDATE vm_meetings
                   SET access_scope='PUBLIC_CODE', guest_access_enabled=TRUE,
                       allow_join_before_host=TRUE
                 WHERE meeting_id=?
                """, historical);
        jdbc.update("""
                INSERT INTO vm_meeting_participants (
                    participant_id, tenant_id, meeting_id, email_address, display_name,
                    participant_role, attendance_state, created_by, updated_by)
                VALUES (?,1,?,'historical-guest@example.invalid','Historical guest',
                        'GUEST','INVITED',3,3)
                """, UUID.randomUUID(), historical);
        jdbc.update("""
                UPDATE vm_tenant_policies
                   SET guests_allowed=TRUE, allow_join_before_host=TRUE,
                       require_authenticated_internal_users=FALSE
                 WHERE tenant_id=1
                """);

        flyway(null).migrate();

        assertThat(jdbc.queryForMap("""
                SELECT access_scope, guest_access_enabled, allow_join_before_host
                  FROM vm_meetings WHERE meeting_id=?
                """, historical))
                .containsEntry("access_scope", "PUBLIC_CODE")
                .containsEntry("guest_access_enabled", true)
                .containsEntry("allow_join_before_host", true);
        assertThat(jdbc.queryForMap("""
                SELECT guests_allowed, allow_join_before_host,
                       require_authenticated_internal_users
                  FROM vm_tenant_policies WHERE tenant_id=1
                """))
                .containsEntry("guests_allowed", false)
                .containsEntry("allow_join_before_host", false)
                .containsEntry("require_authenticated_internal_users", true);

        // Unrelated lifecycle/cleanup writes remain possible for retained unsafe history.
        assertThat(jdbc.update(
                "UPDATE vm_meetings SET title=title WHERE meeting_id=?", historical))
                .isOne();

        UUID safe = jdbc.queryForObject("""
                SELECT meeting_id FROM vm_meetings
                 WHERE access_scope='INVITED' AND NOT guest_access_enabled
                   AND NOT allow_join_before_host
                 ORDER BY meeting_id LIMIT 1
                """, UUID.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE vm_meetings SET guest_access_enabled=TRUE WHERE meeting_id=?", safe))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO vm_meeting_participants (
                    participant_id, tenant_id, meeting_id, email_address, display_name,
                    participant_role, attendance_state, created_by, updated_by)
                VALUES (?,1,?,'new-guest@example.invalid','New guest',
                        'GUEST','INVITED',3,3)
                """, UUID.randomUUID(), safe))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE vm_tenant_policies
                   SET require_authenticated_internal_users=FALSE
                 WHERE tenant_id=1
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void liveUnsafeProviderRoomBlocksDeploymentUntilItIsDrained() {
        UUID live = jdbc.queryForObject("""
                SELECT meeting_id FROM vm_meetings
                 WHERE lifecycle_state='LIVE' ORDER BY meeting_id LIMIT 1
                """, UUID.class);
        jdbc.update(
                "UPDATE vm_meetings SET guest_access_enabled=TRUE WHERE meeting_id=?", live);

        assertThatThrownBy(() -> flyway(null).migrate())
                .hasStackTraceContaining(
                        "live meeting with unverified entry configuration requires "
                                + "provider drain before V40");
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
