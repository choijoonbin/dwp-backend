package com.dwp.services.platform.codecatalog;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseCheckContractRegistryPostgresIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V190__reconcile_database_check_contract_registry.sql");
    private static final Path MIGRATION_DIRECTORY = MIGRATION.getParent();
    private static final Pattern VERSIONED_MIGRATION = Pattern.compile("V(\\d+)__.+\\.sql");

    private static final String EXPECTED_CONTRACTS = """
            dwp-approval-server\tapr_high_risk_idempotency_ledger.status\tCOMMITTED,IN_PROGRESS
            dwp-approval-server\tapr_integration_outbox.recovery_auditor_assignment_state\tASSIGNED,ASSIGNING,EXHAUSTED,LEGACY_UNASSIGNED,NOT_REQUIRED,PENDING,RETRY
            dwp-approval-server\tapr_management_scope_schema_fence.activated_by_release\tapproval-management-scope-v1
            dwp-approval-server\tapr_management_scope_schema_fence.fence_key\tAPPROVAL_MANAGEMENT_SCOPE_V1
            dwp-approval-server\tapr_recovery_auditor_assignment_events.event_type\tAUTOMATIC_PROBE_EPOCH_OPENED,EPOCH_EXHAUSTED
            dwp-approval-server\tapr_step_up_replay_ledger.command_method\tDELETE,PATCH,POST,PUT
            dwp-auth-server\tauth_governed_route_contract.lifecycle_state\tACTIVE,RETIRED
            dwp-auth-server\tauth_governed_route_contract.route_kind\tACTION,DATA,PAGE
            dwp-auth-server\tauth_governed_route_contract.subject_type\tGOVERNED_CONTEXT,PRODUCT
            dwp-auth-server\tauth_product_access_policy.lifecycle_state\tACTIVE,RETIRED
            dwp-auth-server\tauth_product_authority_endpoint.service_key\tapproval,auth,people,platform,provider
            dwp-auth-server\tauth_product_authorization_activation_event.operation\tACTIVATE,ROLLBACK
            dwp-auth-server\tauth_product_authorization_bundle.bundle_status\tACTIVE,APPROVED,DRAFT,RETIRED
            dwp-auth-server\tauth_product_authorization_bundle.checksum_algorithm\tSHA-256
            dwp-auth-server\tauth_product_authorization_seed_release.intended_bundle_status\tDRAFT
            dwp-auth-server\tauth_product_capability_contract.lifecycle_state\tACTIVE,RETIRED
            dwp-auth-server\tauth_product_entitlement_expression.lifecycle_state\tACTIVE,RETIRED
            dwp-auth-server\tauth_product_predicate_policy.lifecycle_state\tACTIVE,RETIRED
            dwp-auth-server\tauth_product_predicate_policy.owner_service_key\tapproval,auth,people,platform
            dwp-auth-server\tcom_access_review_items.reviewer_assignment_state\tACTIVE,REVOKED
            dwp-auth-server\tcom_admin_app_preset_assignments.assignment_source\tIAM,MANUAL,MIGRATION,PROVISIONING
            dwp-auth-server\tcom_admin_app_preset_assignments.lifecycle_state\tACTIVE,APPROVED,DENIED,EXPIRED,PENDING_APPROVAL,REVOKED
            dwp-auth-server\tcom_admin_app_preset_assignments.principal_type\tGROUP,USER
            dwp-auth-server\tcom_admin_app_preset_assignments.request_channel\tGOVERNANCE,SELF_SERVICE
            dwp-auth-server\tcom_admin_role_assignments.lifecycle_state\tACTIVE,APPROVED,DENIED,EXPIRED,PENDING_APPROVAL,REVOKED
            dwp-auth-server\tcom_admin_scoped_duty_assignments.assignment_source\tAGENT,GROUP,IAM,MANUAL,MIGRATION,PROVISIONING
            dwp-auth-server\tcom_admin_scoped_duty_assignments.lifecycle_state\tACTIVE,APPROVED,DENIED,EXPIRED,PENDING_APPROVAL,REVOKED
            dwp-auth-server\tcom_admin_scoped_duty_assignments.principal_type\tGROUP,USER
            dwp-auth-server\tcom_admin_scoped_duty_reviews.lifecycle_state\tDISMISSED,OPEN,RESOLVED
            dwp-auth-server\tcom_users.identity_plane\tPROVIDER,TENANT
            dwp-auth-server\tsys_admin_app_preset_catalog.lifecycle_state\tACTIVE,DRAFT,RETIRED
            dwp-auth-server\tsys_admin_app_preset_catalog.risk_tier\tHIGH,LOW,MEDIUM
            dwp-auth-server\tsys_admin_scoped_duty_catalog.lifecycle_state\tACTIVE,RETIRED
            dwp-auth-server\tsys_admin_scoped_duty_catalog.risk_tier\tHIGH,LOW,MEDIUM
            dwp-auth-server\tsys_admin_scoped_duty_conflicts.lifecycle_state\tACTIVE,RETIRED
            dwp-notification-server\tntf_bulk_undo_items.before_inbox_state\tACTIVE,DONE
            dwp-notification-server\tntf_bulk_undo_receipts.state\tAVAILABLE,COMPLETED
            dwp-notification-server\tntf_user_notifications.target_state\tAVAILABLE,DELETED,FORBIDDEN
            dwp-people-server\tppl_step_up_replay_ledger.command_method\tDELETE,PATCH,POST,PUT
            dwp-platform-server\tadm_home_template_revisions.source\tCREATE,PUBLISH,REVOKE,UPDATE
            dwp-platform-server\tadm_home_templates.lifecycle_state\tDRAFT,PUBLISHED,REVOKED
            dwp-platform-server\tplt_product_surface_ux_event.cohort\tbaseline,design-partner,eligible-10,eligible-25,eligible-50,eligible-90,full,holdout,internal
            dwp-platform-server\tplt_product_surface_ux_event.device_class\tDESKTOP,MOBILE,TABLET
            dwp-platform-server\tplt_product_surface_ux_event.elapsed_bucket\tGTE_5M,LT_1S,M1_TO_5,S15_TO_30,S1_TO_5,S30_TO_60,S5_TO_15
            dwp-platform-server\tplt_product_surface_ux_event.event_name\tsurface.assignment.expired,surface.exposed,surface.policy.lock.viewed,surface.returned,surface.route.denied,surface.scope.invalid,surface.scope.switch.completed,surface.scope.switch.failed,surface.scope.switch.started,surface.switch.completed,surface.switch.failed,surface.switch.started,surface.task.abandoned,surface.task.completed,surface.task.failed,surface.task.started
            dwp-platform-server\tplt_product_surface_ux_event.scope_kind\tDOMAIN,LEGAL_ENTITY,ORG_UNIT,POLICY_NODE,RESOURCE,RESOURCE_SET,SELF,SUPPORT_SESSION,TARGET_POPULATION,TEAM,TENANT
            dwp-platform-server\tusr_home_composer_proposals.state\tAPPLIED,CANCELLED,FAILED,PREVIEWED,UNDONE
            dwp-platform-server\tusr_home_view_device_layouts.device_class\tDESKTOP,MOBILE
            dwp-platform-server\tusr_home_view_revisions.source\tAI,RESTORE,TEMPLATE,UNDO,USER
            dwp-platform-server\tusr_home_views.integrity_state\tRECOVERY_REQUIRED,VALID
            dwp-platform-server\twrk_items.data_classification\tCONFIDENTIAL,INTERNAL,PUBLIC,RESTRICTED
            dwp-provider-server\tprv_audit_events.event_category\tADMINISTRATION,CHANGE_MANAGEMENT,COMMERCIAL_GOVERNANCE,DATA_GOVERNANCE,FEATURE_ROLLOUT,PRIVILEGED_ACCESS,SERVICE_HEALTH,TENANT_LIFECYCLE
            dwp-provider-server\tprv_feature_rollout_decision_outbox.delivery_status\tDEAD,FAILED,PENDING,PUBLISHED,SENDING
            dwp-provider-server\tprv_feature_rollout_decision_outbox.state\tADVANCED,DISABLED,ENABLED,PAUSED,RESUMED,ROLLED_BACK
            dwp-provider-server\tprv_feature_rollout_decision_outbox.tenant_scope\tALL,EXACT
            """;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void configureDataSource() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void freshMigrationRegistersTheExactClosedCheckManifestAndIsSemanticallyIdempotent()
            throws Exception {
        cleanAndMigrate("190");

        assertExactManifest();
        String before = registryRevisionFingerprint();

        executeMigrationAgain();

        assertExactManifest();
        assertThat(registryRevisionFingerprint()).isEqualTo(before);
    }

    @Test
    void upgradeReactivatesCanonicalValuesAndRetiresRegistryOnlyDrift() {
        cleanAndMigrate(latestMigrationBeforeV190());
        seedUpgradeDrift();

        migrateThroughV190();

        assertExactManifest();
        assertThat(jdbc.queryForList("""
                SELECT code_set_key || ':' || code
                  FROM sys_code_values
                 WHERE code IN ('LEGACY_REGISTRY_ONLY', 'STALE_REGISTRY_ONLY')
                   AND lifecycle_state = 'RETIRED'
                 ORDER BY code_set_key, code
                """, String.class)).containsExactly(
                "APPROVAL.APR_HIGH_RISK_IDEMPOTENCY_LEDGER.STATUS:STALE_REGISTRY_ONLY",
                "AUTH.SCOPED_ADMIN.ASSIGNMENT_LIFECYCLE_STATE:LEGACY_REGISTRY_ONLY",
                "PROVIDER.AUDIT_EVENT_CATEGORY:LEGACY_REGISTRY_ONLY");
    }

    private static void cleanAndMigrate(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        Flyway flyway = configuration.load();
        flyway.clean();
        flyway.migrate();
    }

    private static void migrateThroughV190() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("190")
                .load()
                .migrate();
    }

    private static String latestMigrationBeforeV190() {
        try (var migrations = Files.list(MIGRATION_DIRECTORY)) {
            return Integer.toString(migrations
                    .map(path -> VERSIONED_MIGRATION.matcher(path.getFileName().toString()))
                    .filter(Matcher::matches)
                    .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                    .filter(version -> version < 190)
                    .max()
                    .orElseThrow());
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot resolve the migration preceding V190", exception);
        }
    }

    private static void seedUpgradeDrift() {
        jdbc.update("""
                UPDATE sys_code_sets
                   SET lifecycle_state = 'RETIRED'
                 WHERE code_set_key IN (
                       'AUTH.SCOPED_ADMIN.ASSIGNMENT_LIFECYCLE_STATE',
                       'PROVIDER.AUDIT_EVENT_CATEGORY')
                """);
        jdbc.update("""
                UPDATE sys_code_bindings
                   SET lifecycle_state = 'RETIRED'
                 WHERE code_set_key IN (
                       'AUTH.SCOPED_ADMIN.ASSIGNMENT_LIFECYCLE_STATE',
                       'PROVIDER.AUDIT_EVENT_CATEGORY')
                """);
        jdbc.update("""
                UPDATE sys_code_values
                   SET lifecycle_state = 'RETIRED'
                 WHERE (code_set_key = 'AUTH.SCOPED_ADMIN.ASSIGNMENT_LIFECYCLE_STATE'
                            AND code = 'APPROVED')
                    OR (code_set_key = 'PROVIDER.AUDIT_EVENT_CATEGORY'
                            AND code IN ('FEATURE_ROLLOUT', 'COMMERCIAL_GOVERNANCE'))
                """);
        jdbc.update("""
                INSERT INTO sys_code_values (
                    code_set_key, code, display_name, label_i18n,
                    behavior_metadata, lifecycle_state)
                VALUES
                    ('AUTH.SCOPED_ADMIN.ASSIGNMENT_LIFECYCLE_STATE',
                     'LEGACY_REGISTRY_ONLY', 'Legacy', '{}', '{}', 'ACTIVE'),
                    ('PROVIDER.AUDIT_EVENT_CATEGORY',
                     'LEGACY_REGISTRY_ONLY', 'Legacy', '{}', '{}', 'ACTIVE')
                ON CONFLICT (code_set_key, code) DO UPDATE
                   SET lifecycle_state = 'ACTIVE'
                """);

        jdbc.update("""
                INSERT INTO sys_code_sets (
                    code_set_key, owner_service, display_name, description,
                    configuration_level, validation_source, source_reference,
                    contract_kind, runtime_visibility, lifecycle_state)
                VALUES (
                    'APPROVAL.APR_HIGH_RISK_IDEMPOTENCY_LEDGER.STATUS',
                    'dwp-approval-server', 'Stale registration',
                    'Upgrade fixture', 'SYSTEM', 'CHECK',
                    'apr_high_risk_idempotency_ledger.status',
                    'REFERENCE', 'ADMIN_ONLY', 'RETIRED')
                """);
        jdbc.update("""
                INSERT INTO sys_code_values (
                    code_set_key, code, display_name, label_i18n,
                    behavior_metadata, lifecycle_state)
                VALUES
                    ('APPROVAL.APR_HIGH_RISK_IDEMPOTENCY_LEDGER.STATUS',
                     'COMMITTED', 'Committed', '{}', '{}', 'RETIRED'),
                    ('APPROVAL.APR_HIGH_RISK_IDEMPOTENCY_LEDGER.STATUS',
                     'STALE_REGISTRY_ONLY', 'Stale', '{}', '{}', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO sys_code_bindings (
                    code_set_key, consumer_service, usage_type,
                    source_reference, enforcement_type, lifecycle_state)
                VALUES (
                    'APPROVAL.APR_HIGH_RISK_IDEMPOTENCY_LEDGER.STATUS',
                    'dwp-approval-server', 'DATABASE_COLUMN',
                    'apr_high_risk_idempotency_ledger.status', 'CHECK', 'RETIRED')
                """);
    }

    private static void assertExactManifest() {
        Map<String, String> expected = Arrays.stream(EXPECTED_CONTRACTS.strip().split("\\R"))
                .map(line -> line.split("\\t", 3))
                .collect(Collectors.toMap(
                        parts -> parts[0] + "\t" + parts[1],
                        parts -> parts[2]));

        List<ContractSnapshot> snapshots = jdbc.query("""
                SELECT code_set.code_set_key,
                       binding.consumer_service,
                       binding.source_reference,
                       code_set.runtime_visibility,
                       string_agg(code_value.code, ',' ORDER BY code_value.code) AS codes
                  FROM sys_code_sets code_set
                  JOIN sys_code_bindings binding
                    ON binding.code_set_key = code_set.code_set_key
                   AND binding.lifecycle_state = 'ACTIVE'
                   AND binding.usage_type = 'DATABASE_COLUMN'
                   AND binding.enforcement_type = 'CHECK'
                  JOIN sys_code_values code_value
                    ON code_value.code_set_key = code_set.code_set_key
                   AND code_value.lifecycle_state = 'ACTIVE'
                 WHERE code_set.lifecycle_state = 'ACTIVE'
                 GROUP BY code_set.code_set_key, binding.consumer_service,
                          binding.source_reference, code_set.runtime_visibility
                """, (row, ignored) -> new ContractSnapshot(
                row.getString("code_set_key"),
                row.getString("consumer_service"),
                row.getString("source_reference"),
                row.getString("runtime_visibility"),
                row.getString("codes")));

        for (Map.Entry<String, String> contract : expected.entrySet()) {
            List<ContractSnapshot> matches = snapshots.stream()
                    .filter(snapshot -> snapshot.contractReference().equals(contract.getKey()))
                    .toList();
            assertThat(matches)
                    .as("one canonical registration for %s", contract.getKey())
                    .hasSize(1);
            assertThat(matches.getFirst().codes())
                    .as("active values for %s", contract.getKey())
                    .isEqualTo(contract.getValue());
            assertThat(matches.getFirst().runtimeVisibility())
                    .as("runtime visibility for %s", contract.getKey())
                    .isEqualTo("ADMIN_ONLY");
        }
        assertThat(expected).hasSize(55);
    }

    private static String registryRevisionFingerprint() {
        return jdbc.queryForObject("""
                WITH registry_rows AS (
                    SELECT 'SET|' || code_set_key || '|' || schema_version || '|'
                               || updated_at::TEXT AS row_value
                      FROM sys_code_sets
                    UNION ALL
                    SELECT 'VALUE|' || code_set_key || '|' || code || '|'
                               || lifecycle_state || '|' || updated_at::TEXT
                      FROM sys_code_values
                    UNION ALL
                    SELECT 'BINDING|' || code_binding_id || '|' || code_set_key || '|'
                               || lifecycle_state || '|' || updated_at::TEXT
                      FROM sys_code_bindings
                )
                SELECT string_agg(row_value, E'\n' ORDER BY row_value)
                  FROM registry_rows
                """, String.class);
    }

    private static void executeMigrationAgain() throws Exception {
        String migration = Files.readString(MIGRATION);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute(migration);
            connection.commit();
        }
    }

    private record ContractSnapshot(
            String codeSetKey,
            String consumerService,
            String sourceReference,
            String runtimeVisibility,
            String codes) {

        private String contractReference() {
            return consumerService + "\t" + sourceReference;
        }
    }
}
