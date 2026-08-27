package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MailOrganizationMigrationPostgresIntegrationTest {

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void freshMigrationCreatesGovernedFoldersRulesAndLifecycleContracts() {
        String schema = "mail_organization_fresh";
        Flyway flyway = flyway(schema, null);
        flyway.clean();
        flyway.migrate();

        assertLatestSchema(schema);
        assertMailCodeContracts(schema);
        assertRuntimeQueries(schema);
    }

    @Test
    void v202UpgradeRegistersContractsThatWerePreviouslyOptional() {
        String schema = "mail_organization_upgrade";
        Flyway throughV202 = flyway(schema, "202");
        throughV202.clean();
        throughV202.migrate();

        JdbcTemplate jdbc = jdbc();
        assertThat(jdbc.queryForObject(
                "SELECT to_regclass(?) IS NULL",
                Boolean.class,
                schema + ".mail_rules")).isTrue();

        flyway(schema, null).migrate();

        assertLatestSchema(schema);
        assertMailCodeContracts(schema);
        assertRuntimeQueries(schema);
    }

    private void assertLatestSchema(String schema) {
        JdbcTemplate jdbc = jdbc();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.tables
                 WHERE table_schema = ?
                   AND table_name IN ('mail_rules', 'mail_rule_runs')
                """, Integer.class, schema)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.columns
                 WHERE table_schema = ?
                   AND ((table_name = 'mail_folders' AND column_name IN (
                           'parent_folder_id', 'color_token', 'provider_sync_state', 'version'))
                     OR (table_name = 'mail_threads' AND column_name IN (
                           'previous_folder_id', 'trashed_at', 'spam_reported_at')))
                """, Integer.class, schema)).isEqualTo(7);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM %s.flyway_schema_history
                 WHERE version = '203' AND success
                """.formatted(schema), Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM %s.mail_folders folder
                  JOIN %s.mail_accounts account ON account.account_id = folder.account_id
                 WHERE account.account_kind = 'PERSONAL'
                   AND folder.folder_type = 'CUSTOM'
                   AND folder.lifecycle_state = 'ACTIVE'
                """.formatted(schema, schema), Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM %s.mail_rules WHERE lifecycle_state = 'ACTIVE'
                """.formatted(schema), Integer.class)).isPositive();
    }

    private void assertMailCodeContracts(String schema) {
        JdbcTemplate jdbc = jdbc();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                 FROM %s.sys_code_sets
                 WHERE source_reference IN (
                       'mail_folders.color_token',
                       'mail_folders.provider_sync_state',
                       'mail_threads.workflow_state',
                       'mail_rules.match_mode',
                       'mail_rules.synchronization_state',
                       'mail_rules.lifecycle_state',
                       'mail_rule_runs.trigger_kind',
                       'mail_rule_runs.run_status')
                   AND lifecycle_state = 'ACTIVE'
                """.formatted(schema), Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM %s.sys_code_bindings
                 WHERE source_reference IN (
                       'mail_folders.color_token',
                       'mail_folders.provider_sync_state',
                       'mail_threads.workflow_state',
                       'mail_rules.match_mode',
                       'mail_rules.synchronization_state',
                       'mail_rules.lifecycle_state',
                       'mail_rule_runs.trigger_kind',
                       'mail_rule_runs.run_status')
                   AND enforcement_type = 'CHECK'
                   AND lifecycle_state = 'ACTIVE'
                """.formatted(schema), Integer.class)).isEqualTo(8);
    }

    private void assertRuntimeQueries(String schema) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        Long tenantId = jdbc.queryForObject("""
                SELECT tenant_id
                  FROM mail_accounts
                 WHERE account_kind = 'PERSONAL' AND owner_user_id IS NOT NULL
                 ORDER BY tenant_id, owner_user_id
                 LIMIT 1
                """, Long.class);
        Long userId = jdbc.queryForObject("""
                SELECT owner_user_id
                  FROM mail_accounts
                 WHERE tenant_id = ? AND account_kind = 'PERSONAL'
                   AND owner_user_id IS NOT NULL
                 ORDER BY owner_user_id
                 LIMIT 1
                """, Long.class, tenantId);

        MailOrganizationQueryRepository repository = new MailOrganizationQueryRepository(
                jdbc, new MailJsonCodec(new ObjectMapper().findAndRegisterModules()));

        assertThat(repository.folders(tenantId, userId)).isNotEmpty();
        assertThat(repository.rules(tenantId, userId)).isNotEmpty();
        assertThat(repository.recentRuns(tenantId, userId)).isEmpty();
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

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    private PGSimpleDataSource dataSource(String schema) {
        PGSimpleDataSource dataSource = dataSource();
        dataSource.setCurrentSchema(schema);
        return dataSource;
    }
}
