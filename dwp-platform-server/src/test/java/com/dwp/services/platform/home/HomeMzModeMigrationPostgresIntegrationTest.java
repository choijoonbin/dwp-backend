package com.dwp.services.platform.home;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class HomeMzModeMigrationPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void mzOwnsAnIndependentViewNamespaceWithoutChangingClassicOrFlow() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(constraint(jdbc, "ck_usr_home_views_mode")).contains("MZ_V1");
        assertThat(constraint(jdbc, "ck_usr_home_preferences_current_mode")).contains("MZ_V1");
        assertThat(constraint(jdbc, "ck_widget_control_mode")).contains("MZ_V1");

        for (String mode : List.of("CLASSIC", "FLOW_V1", "MZ_V1")) {
            jdbc.update("""
                    INSERT INTO usr_home_views (
                        view_id, tenant_id, user_id, surface_key, mode_key,
                        legacy_unscoped, view_key, name, is_default,
                        schema_version, layout_payload, version)
                    VALUES (?, 77, 88001, 'workspace-home', ?, FALSE,
                            'default', ?, TRUE, 5, '{"widgets":[]}'::jsonb, 0)
                    """, UUID.randomUUID(), mode, mode + " home");
        }

        assertThat(jdbc.queryForList("""
                SELECT mode_key FROM usr_home_views
                 WHERE tenant_id = 77 AND user_id = 88001
                 ORDER BY mode_key
                """, String.class)).containsExactly("CLASSIC", "FLOW_V1", "MZ_V1");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO usr_home_views (
                    view_id, tenant_id, user_id, surface_key, mode_key,
                    legacy_unscoped, view_key, name, is_default,
                    schema_version, layout_payload, version)
                VALUES (?, 77, 88001, 'workspace-home', 'MZ_V1', FALSE,
                        'default', 'Duplicate MZ', FALSE, 5, '{"widgets":[]}'::jsonb, 0)
                """, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO usr_home_views (
                    view_id, tenant_id, user_id, surface_key, mode_key,
                    legacy_unscoped, view_key, name, is_default,
                    schema_version, layout_payload, version)
                VALUES (?, 77, 88001, 'workspace-home', 'FLOW_MZ', FALSE,
                        'invalid', 'Invalid', FALSE, 5, '{"widgets":[]}'::jsonb, 0)
                """, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                UPDATE usr_home_views SET name = 'MZ independent'
                 WHERE tenant_id = 77 AND user_id = 88001 AND mode_key = 'MZ_V1'
                """);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM usr_home_views
                 WHERE tenant_id = 77 AND user_id = 88001
                   AND mode_key IN ('CLASSIC', 'FLOW_V1')
                   AND name IN ('CLASSIC home', 'FLOW_V1 home')
                """, Integer.class)).isEqualTo(2);
    }

    private String constraint(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint WHERE conname = ?
                """, String.class, name);
    }
}
