package com.dwp.services.platform.home.runtime;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class HomeRuntimeContinuityPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void activeShadowActiveKeepsViewsOverlaysConfigurationsAndRevisionLineageExact() {
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
        UUID viewId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO usr_home_views (
                    view_id, tenant_id, user_id, surface_key, view_key, name,
                    is_default, schema_version, layout_payload, mode_key,
                    legacy_unscoped, created_by, updated_by)
                VALUES (?, 1, 82, 'workspace-home', 'wave-six-continuity',
                        'Wave Six Continuity', TRUE, 5,
                        '{"widgets":[{"widgetKey":"focus","visible":true}]}'::jsonb,
                        'CLASSIC', FALSE, 82, 82)
                """, viewId);
        jdbc.update("""
                INSERT INTO usr_home_view_device_layouts (
                    device_layout_id, view_id, tenant_id, user_id, device_class,
                    overlay_payload, created_by, updated_by)
                VALUES (?, ?, 1, 82, 'DESKTOP_STANDARD',
                        '{"overrides":[]}'::jsonb, 82, 82)
                """, UUID.randomUUID(), viewId);
        jdbc.update("""
                INSERT INTO usr_home_widget_configurations (
                    widget_configuration_id, view_id, tenant_id, user_id, widget_key,
                    configuration_payload, created_by, updated_by)
                VALUES (?, ?, 1, 82, 'focus', '{"itemLimit":10}'::jsonb, 82, 82)
                """, UUID.randomUUID(), viewId);
        jdbc.update("""
                INSERT INTO usr_home_view_revisions (
                    revision_id, view_id, tenant_id, user_id, revision_number,
                    schema_version, snapshot, source, created_by)
                VALUES (?, ?, 1, 82, 1, 5,
                        '{"mode":"CLASSIC","deviceClass":"DESKTOP_STANDARD"}'::jsonb,
                        'USER', 82)
                """, UUID.randomUUID(), viewId);
        String before = snapshot(jdbc, viewId);

        assertThat(HomeRuntimeRolloutDecision.TrustedInput.parse(
                "READ_ONLY_ACTIVE", "INTERNAL", "rollout-17").state())
                .isEqualTo(HomeRuntimeRolloutDecision.State.READ_ONLY_ACTIVE);
        assertThat(HomeRuntimeRolloutDecision.TrustedInput.parse(
                "SHADOW_COMPARE", "INTERNAL", "rollout-18").state())
                .isEqualTo(HomeRuntimeRolloutDecision.State.SHADOW_COMPARE);
        assertThat(HomeRuntimeRolloutDecision.TrustedInput.parse(
                "READ_ONLY_ACTIVE", "INTERNAL", "rollout-19").state())
                .isEqualTo(HomeRuntimeRolloutDecision.State.READ_ONLY_ACTIVE);

        assertThat(snapshot(jdbc, viewId)).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "SELECT migration_mode FROM plt_widget_registry_state WHERE environment='GLOBAL'",
                String.class)).isEqualTo("SHADOW");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO plt_widget_runtime_controls (
                    control_id, control_scope, target_type, target_id, control_state,
                    control_revision, reason_code, reason_text, created_by)
                VALUES (?, 'RUNTIME_RENDER', 'MODE', 'FLOW_BETA', 'DISABLED',
                        1, 'TEST', 'invalid mode remains closed', 82)
                """, UUID.randomUUID())).isInstanceOf(DataAccessException.class);
    }

    private String snapshot(JdbcTemplate jdbc, UUID viewId) {
        return jdbc.queryForObject("""
                SELECT jsonb_build_object(
                    'view', (SELECT to_jsonb(v) - 'updated_at' - 'created_at'
                               FROM usr_home_views v WHERE view_id = ?),
                    'devices', (SELECT jsonb_agg(to_jsonb(d) - 'updated_at' - 'created_at'
                                                   ORDER BY device_class)
                                  FROM usr_home_view_device_layouts d WHERE view_id = ?),
                    'configs', (SELECT jsonb_agg(to_jsonb(c) - 'updated_at' - 'created_at'
                                                   ORDER BY widget_key)
                                  FROM usr_home_widget_configurations c WHERE view_id = ?),
                    'revisions', (SELECT jsonb_agg(to_jsonb(r) - 'created_at'
                                                     ORDER BY revision_number)
                                    FROM usr_home_view_revisions r WHERE view_id = ?)
                )::text
                """, String.class, viewId, viewId, viewId, viewId);
    }
}
