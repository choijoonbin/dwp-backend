package com.dwp.services.provider.rollout;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class Core006LocalPilotRolloutSeedPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateWithExplicitLocalSeedLocation() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:src/main/resources/db/local-seed",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void activatesTheExactProductScopedPilotTruthTableForTheExactTenant() {
        Map<Boolean, Integer> values = jdbc.query("""
                SELECT (revision.rollout_value = 'true'::jsonb) AS enabled,
                       COUNT(*) AS count
                  FROM prv_feature_rollout_revisions revision
                 WHERE revision.rollout_revision_id::text LIKE 'c0061000-%'
                   AND revision.lifecycle_state = 'ACTIVE'
                 GROUP BY enabled
                """, result -> {
            java.util.HashMap<Boolean, Integer> counts = new java.util.HashMap<>();
            while (result.next()) {
                counts.put(result.getBoolean("enabled"), result.getInt("count"));
            }
            return counts;
        });
        assertThat(values).containsEntry(true, 10).containsEntry(false, 14);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_feature_rollout_revisions revision
                  JOIN prv_feature_flags flag
                    ON flag.feature_flag_id = revision.feature_flag_id
                 WHERE revision.rollout_revision_id::text LIKE 'c0061000-%'
                   AND revision.revision_number = 1
                   AND revision.version >= 3
                   AND revision.current_stage_order = 1
                   AND revision.requested_by <> revision.approved_by
                   AND revision.targeting =
                       '{"tenantIds":["00000000-0000-0000-0000-000000000001"]}'::jsonb
                   AND flag.default_value = 'false'::jsonb
                """, Integer.class)).isEqualTo(24);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_feature_rollout_stages stage
                 WHERE stage.rollout_stage_id::text LIKE 'c0062000-%'
                   AND stage.stage_order = 1
                   AND stage.exposure_percentage = 100.00
                   AND stage.lifecycle_state = 'ACTIVE'
                """, Integer.class)).isEqualTo(24);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_feature_rollout_approvals approval
                 WHERE approval.rollout_approval_id::text LIKE 'c0063000-%'
                   AND approval.lifecycle_state = 'APPROVED'
                   AND approval.requested_by <> approval.decided_by
                """, Integer.class)).isEqualTo(24);

        assertThat(jdbc.queryForList("""
                SELECT flag.feature_key
                  FROM prv_feature_rollout_revisions revision
                  JOIN prv_feature_flags flag
                    ON flag.feature_flag_id = revision.feature_flag_id
                 WHERE revision.rollout_revision_id::text LIKE 'c0061000-%'
                   AND revision.rollout_value = 'true'::jsonb
                 ORDER BY flag.feature_key
                """, String.class)).containsExactly(
                "access.product-surfaces.capability-enforcement.approvals.v1",
                "access.product-surfaces.capability-enforcement.communications.v1",
                "access.product-surfaces.capability-enforcement.hcm.v1",
                "access.product-surfaces.capability-enforcement.services.v1",
                "access.product-surfaces.capability-enforcement.v1",
                "access.product-surfaces.context-shadow.v1",
                "ux.product-surfaces.approvals.v1",
                "ux.product-surfaces.communications.v1",
                "ux.product-surfaces.hcm.v1",
                "ux.product-surfaces.services.v1");

        assertThat(jdbc.queryForList("""
                WITH decisions AS (
                    SELECT flag.feature_key,
                           revision.rollout_value = 'true'::jsonb AS enabled
                      FROM prv_feature_rollout_revisions revision
                      JOIN prv_feature_flags flag
                        ON flag.feature_flag_id = revision.feature_flag_id
                     WHERE revision.rollout_revision_id::text LIKE 'c0061000-%'
                       AND revision.lifecycle_state = 'ACTIVE'
                ), products(product_key) AS (
                    VALUES ('approvals'), ('calendar'), ('communications'), ('dwaion'),
                           ('hcm'), ('mail'), ('messaging'), ('notifications'),
                           ('services'), ('spaces'), ('workplace')
                )
                SELECT CONCAT(
                           products.product_key, '=',
                           CASE WHEN shadow.enabled THEN '1' ELSE '0' END,
                           CASE WHEN enforcement.enabled THEN '1' ELSE '0' END,
                           CASE WHEN ui.enabled THEN '1' ELSE '0' END)
                  FROM products
                  JOIN decisions shadow
                    ON shadow.feature_key = 'access.product-surfaces.context-shadow.v1'
                  JOIN decisions enforcement
                    ON enforcement.feature_key = CONCAT(
                       'access.product-surfaces.capability-enforcement.',
                       products.product_key, '.v1')
                  JOIN decisions ui
                    ON ui.feature_key = CONCAT(
                       'ux.product-surfaces.', products.product_key, '.v1')
                 ORDER BY products.product_key
                """, String.class)).containsExactly(
                "approvals=111",
                "calendar=100",
                "communications=111",
                "dwaion=100",
                "hcm=111",
                "mail=100",
                "messaging=100",
                "notifications=100",
                "services=111",
                "spaces=100",
                "workplace=100");
    }

    @Test
    void registersEveryProductScopedEnforcementFlagDefaultOffWithDecisionRevision() {
        assertThat(jdbc.queryForList("""
                SELECT flag.feature_key
                  FROM prv_feature_flags flag
                  JOIN prv_feature_rollout_decision_revision decision
                    ON decision.feature_flag_id = flag.feature_flag_id
                 WHERE flag.feature_key LIKE
                       'access.product-surfaces.capability-enforcement.%.v1'
                   AND flag.value_type = 'BOOLEAN'
                   AND flag.default_value = 'false'::jsonb
                   AND flag.configuration_schema = '{"type":"boolean"}'::jsonb
                   AND flag.risk_tier = 'L3'
                   AND flag.lifecycle_state = 'ACTIVE'
                   AND decision.opaque_revision >= 1
                 ORDER BY flag.feature_key
                """, String.class)).containsExactly(
                "access.product-surfaces.capability-enforcement.approvals.v1",
                "access.product-surfaces.capability-enforcement.calendar.v1",
                "access.product-surfaces.capability-enforcement.communications.v1",
                "access.product-surfaces.capability-enforcement.dwaion.v1",
                "access.product-surfaces.capability-enforcement.hcm.v1",
                "access.product-surfaces.capability-enforcement.mail.v1",
                "access.product-surfaces.capability-enforcement.messaging.v1",
                "access.product-surfaces.capability-enforcement.notifications.v1",
                "access.product-surfaces.capability-enforcement.services.v1",
                "access.product-surfaces.capability-enforcement.spaces.v1",
                "access.product-surfaces.capability-enforcement.workplace.v1");
    }

    @Test
    void repeatableSeedIsIdempotentWhenExecutedAgain() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(new FileSystemResource(
                            "src/main/resources/db/local-seed/"
                                    + "R__activate_core006_local_pilot_rollouts.sql")),
                    false,
                    false,
                    ScriptUtils.DEFAULT_COMMENT_PREFIXES,
                    ScriptUtils.EOF_STATEMENT_SEPARATOR,
                    ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
                    ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
            connection.commit();
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_feature_rollout_revisions
                 WHERE rollout_revision_id::text LIKE 'c0061000-%'
                """, Integer.class)).isEqualTo(24);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_feature_rollout_stages
                 WHERE rollout_stage_id::text LIKE 'c0062000-%'
                """, Integer.class)).isEqualTo(24);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_feature_rollout_approvals
                 WHERE rollout_approval_id::text LIKE 'c0063000-%'
                """, Integer.class)).isEqualTo(24);
    }

    @Test
    void mapsPostgresTimestamptzAcrossRolloutStageApprovalAndOutboxRows() {
        FeatureRolloutRepository repository = new FeatureRolloutRepository(
                new NamedParameterJdbcTemplate(dataSource), new ObjectMapper());
        FeatureRolloutRepository.FlagRow flag = repository
                .flag("ux.product-surfaces.approvals.v1")
                .orElseThrow();

        FeatureRolloutRepository.RolloutRow rollout = repository
                .effectiveRollouts(flag.flagId())
                .getFirst();
        assertThat(rollout.submittedAt()).isNotNull();
        assertThat(rollout.approvedAt()).isNotNull();
        assertThat(rollout.activatedAt()).isNotNull();
        assertThat(rollout.completedAt()).isNull();
        assertThat(rollout.pausedAt()).isNull();

        assertThat(repository.stages(rollout.rolloutId()))
                .singleElement()
                .satisfies(stage -> {
                    assertThat(stage.startedAt()).isNotNull();
                    assertThat(stage.completedAt()).isNull();
                });
        assertThat(repository.approval(rollout.rolloutId()))
                .hasValueSatisfying(approval -> {
                    assertThat(approval.requestedAt()).isNotNull();
                    assertThat(approval.decidedAt()).isNotNull();
                });

        FeatureRolloutDecisionOutboxRepository outbox =
                new FeatureRolloutDecisionOutboxRepository(new JdbcTemplate(dataSource));
        long revision = outbox.appendAllTenants(
                flag.flagId(), flag.featureKey(), "ENABLED");
        List<FeatureRolloutDecisionOutboxRepository.DecisionEvent> events =
                outbox.claim("postgres-timestamptz-test", 1, Duration.ofSeconds(30));
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.flagKey()).isEqualTo(flag.featureKey());
            assertThat(event.opaqueRevision()).isEqualTo(revision);
            assertThat(event.createdAt()).isNotNull();
        });
    }
}
