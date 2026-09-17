package com.dwp.services.platform.home.personalization;

import com.dwp.services.platform.home.HomeModeV4ActivationGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the Flow continuity projection after the fleet has been drained and the operator opens
 * the v4 interlock. The PostgreSQL transaction advisory lock serializes concurrent pod startup.
 */
@Component
public class HomeModeV4ActivationCoordinator implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(
            HomeModeV4ActivationCoordinator.class);
    private static final long ACTIVATION_LOCK_KEY = 4919407635140027473L;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final HomeModeV4ActivationGate activationGate;

    public HomeModeV4ActivationCoordinator(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            HomeModeV4ActivationGate activationGate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.activationGate = activationGate;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!activationGate.requested()) return;
        transactionTemplate.executeWithoutResult(ignored -> activateContinuityProjection());
        activationGate.markContinuityReady();
        log.info("Home Composition v4 continuity projection is ready");
    }

    private void activateContinuityProjection() {
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + ACTIVATION_LOCK_KEY + ")");

        // A partial/pre-release writer may already have created the canonical counterpart. Prefer
        // it deterministically, then rewrite every remaining legacy key before capability ready.
        jdbcTemplate.update("""
                DELETE FROM usr_home_view_device_layouts legacy
                 USING usr_home_view_device_layouts canonical
                 WHERE legacy.view_id = canonical.view_id
                   AND ((legacy.device_class = 'DESKTOP'
                         AND canonical.device_class = 'DESKTOP_STANDARD')
                     OR (legacy.device_class = 'MOBILE'
                         AND canonical.device_class = 'MOBILE_STANDARD'))
                """);
        jdbcTemplate.update("""
                UPDATE usr_home_view_device_layouts
                   SET device_class = CASE device_class
                       WHEN 'DESKTOP' THEN 'DESKTOP_STANDARD'
                       WHEN 'MOBILE' THEN 'MOBILE_STANDARD'
                       ELSE device_class
                   END,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE device_class IN ('DESKTOP', 'MOBILE')
                """);

        jdbcTemplate.update("""
                INSERT INTO usr_home_views (
                    view_id, tenant_id, user_id, surface_key, mode_key,
                    legacy_source_view_id, legacy_unscoped,
                    view_key, name, is_default, schema_version, layout_payload, version,
                    integrity_state, is_customized, created_at, created_by, updated_at, updated_by,
                    deleted_at, deleted_by)
                SELECT gen_random_uuid(), classic.tenant_id, classic.user_id, classic.surface_key,
                       selected.mode_key, classic.view_id, FALSE,
                       classic.view_key, classic.name,
                       CASE WHEN classic.is_default AND NOT EXISTS (
                           SELECT 1 FROM usr_home_views existing_default
                            WHERE existing_default.tenant_id = classic.tenant_id
                              AND existing_default.user_id = classic.user_id
                              AND existing_default.surface_key = classic.surface_key
                              AND existing_default.mode_key = selected.mode_key
                              AND existing_default.is_default
                              AND existing_default.deleted_at IS NULL
                       ) THEN TRUE ELSE FALSE END,
                       classic.schema_version, classic.layout_payload, classic.version,
                       classic.integrity_state, classic.is_customized,
                       classic.created_at, classic.created_by, classic.updated_at,
                       classic.updated_by, NULL, NULL
                  FROM usr_home_views classic
                  JOIN adm_home_experiences experience
                    ON experience.tenant_id = classic.tenant_id
                 CROSS JOIN LATERAL (
                       SELECT upper(COALESCE(
                           experience.composition_policy ->> 'experienceVariant', 'CLASSIC'))
                              AS mode_key
                 ) selected
                 WHERE classic.mode_key = 'CLASSIC'
                   AND classic.legacy_unscoped
                   AND classic.surface_key = 'workspace-home'
                   AND classic.deleted_at IS NULL
                   AND selected.mode_key IN ('FLOW_V1', 'MZ_V1')
                   AND NOT EXISTS (
                       SELECT 1 FROM usr_home_views advanced
                        WHERE advanced.tenant_id = classic.tenant_id
                          AND advanced.user_id = classic.user_id
                          AND advanced.surface_key = classic.surface_key
                          AND advanced.mode_key = selected.mode_key
                          AND advanced.view_key = classic.view_key
                          AND advanced.deleted_at IS NULL)
                ON CONFLICT DO NOTHING
                """);

        jdbcTemplate.update("""
                INSERT INTO usr_home_view_revisions (
                    revision_id, view_id, tenant_id, user_id, revision_number, schema_version,
                    snapshot, source, change_summary, command_id, request_fingerprint,
                    created_at, created_by, restorable)
                SELECT gen_random_uuid(), flow.view_id, revision.tenant_id, revision.user_id,
                       revision.revision_number, revision.schema_version, revision.snapshot,
                       revision.source, revision.change_summary, NULL, NULL,
                       revision.created_at, revision.created_by, revision.restorable
                  FROM usr_home_views flow
                  JOIN usr_home_view_revisions revision
                    ON revision.view_id = flow.legacy_source_view_id
                 WHERE flow.mode_key IN ('FLOW_V1', 'MZ_V1')
                   AND flow.legacy_source_view_id IS NOT NULL
                ON CONFLICT (view_id, revision_number) DO NOTHING
                """);

        jdbcTemplate.update("""
                INSERT INTO usr_home_view_device_layouts (
                    device_layout_id, view_id, tenant_id, user_id, device_class, overlay_payload,
                    version, created_at, created_by, updated_at, updated_by)
                SELECT gen_random_uuid(), flow.view_id, layout.tenant_id, layout.user_id,
                       layout.device_class, layout.overlay_payload, layout.version,
                       layout.created_at, layout.created_by, layout.updated_at, layout.updated_by
                  FROM usr_home_views flow
                  JOIN usr_home_view_device_layouts layout
                    ON layout.view_id = flow.legacy_source_view_id
                 WHERE flow.mode_key IN ('FLOW_V1', 'MZ_V1')
                   AND flow.legacy_source_view_id IS NOT NULL
                ON CONFLICT (view_id, device_class) DO NOTHING
                """);

        jdbcTemplate.update("""
                INSERT INTO usr_home_widget_configurations (
                    widget_configuration_id, view_id, tenant_id, user_id, widget_key,
                    configuration_payload, version, created_at, created_by, updated_at, updated_by)
                SELECT gen_random_uuid(), flow.view_id, configuration.tenant_id,
                       configuration.user_id, configuration.widget_key,
                       configuration.configuration_payload, configuration.version,
                       configuration.created_at, configuration.created_by,
                       configuration.updated_at, configuration.updated_by
                  FROM usr_home_views flow
                  JOIN usr_home_widget_configurations configuration
                    ON configuration.view_id = flow.legacy_source_view_id
                 WHERE flow.mode_key IN ('FLOW_V1', 'MZ_V1')
                   AND flow.legacy_source_view_id IS NOT NULL
                ON CONFLICT (view_id, widget_key) DO NOTHING
                """);

        // Cached proposals carry the source view id. They must not apply or undo against the
        // preserved Classic rollback row after an advanced projection becomes authoritative.
        jdbcTemplate.update("""
                UPDATE usr_home_composer_proposals proposal
                   SET state = 'FAILED',
                       version = proposal.version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = 1
                 WHERE proposal.state IN ('PREVIEWED', 'APPLIED')
                   AND EXISTS (
                       SELECT 1 FROM usr_home_views flow
                        WHERE flow.mode_key IN ('FLOW_V1', 'MZ_V1')
                          AND flow.legacy_source_view_id = proposal.view_id)
                """);

        // The activation boundary has classified every pre-Wave1/old-pod row. Wave1 entities
        // explicitly write FALSE, so a later restart cannot copy new Classic customizations.
        jdbcTemplate.update("UPDATE usr_home_views SET legacy_unscoped = FALSE WHERE legacy_unscoped");
    }
}
