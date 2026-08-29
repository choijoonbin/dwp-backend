package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dwp.core.exception.BaseException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void v208UpgradeAppliesTheTenantRelationFence() {
        String schema = "mail_organization_upgrade";
        Flyway throughV208 = flyway(schema, "208");
        throughV208.clean();
        throughV208.migrate();

        JdbcTemplate jdbc = jdbc();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM %s.flyway_schema_history WHERE version = '208' AND success"
                        .formatted(schema), Integer.class)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM %s.flyway_schema_history WHERE version = '211'"
                        .formatted(schema), Integer.class)).isZero();

        flyway(schema, null).migrate();

        assertLatestSchema(schema);
        assertMailCodeContracts(schema);
        assertRuntimeQueries(schema);
    }

    @Test
    void v211RejectsCrossTenantMailboxRelationships() {
        String schema = "mail_relation_mismatch";
        Flyway throughV210 = flyway(schema, "210");
        throughV210.clean();
        throughV210.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        UUID accountId = jdbc.queryForObject(
                "SELECT account_id FROM mail_accounts ORDER BY account_id LIMIT 1",
                UUID.class);
        Long tenantId = jdbc.queryForObject(
                "SELECT tenant_id FROM mail_accounts WHERE account_id = ?",
                Long.class, accountId);
        jdbc.update("""
                INSERT INTO mail_folders (
                    folder_id, tenant_id, account_id, folder_key,
                    display_name, folder_type, lifecycle_state)
                VALUES (?, ?, ?, 'cross-tenant-negative',
                        'Cross tenant negative', 'CUSTOM', 'ACTIVE')
                """, UUID.randomUUID(), tenantId + 1_000_000L, accountId);

        assertThatThrownBy(() -> flyway(schema, null).migrate())
                .isInstanceOf(FlywayException.class)
                .rootCause()
                .hasMessageContaining("mail_folders contains a cross-tenant account relationship");
    }

    @Test
    void backfillIsPreviewBoundIdempotentOrderedAndPersonalAccountOnly() {
        String schema = "mail_rule_backfill";
        Flyway flyway = flyway(schema, null);
        flyway.clean();
        flyway.migrate();

        PGSimpleDataSource source = dataSource(schema);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(source));
        Long tenantId = jdbc.queryForObject("""
                SELECT tenant_id FROM mail_accounts
                 WHERE account_kind = 'PERSONAL' AND owner_user_id IS NOT NULL
                 ORDER BY tenant_id, owner_user_id LIMIT 1
                """, Long.class);
        Long userId = jdbc.queryForObject("""
                SELECT owner_user_id FROM mail_accounts
                 WHERE tenant_id = ? AND account_kind = 'PERSONAL'
                 ORDER BY owner_user_id LIMIT 1
                """, Long.class, tenantId);
        UUID accountId = jdbc.queryForObject("""
                SELECT account_id FROM mail_accounts
                 WHERE tenant_id = ? AND owner_user_id = ? AND account_kind = 'PERSONAL'
                 ORDER BY is_default DESC, account_id LIMIT 1
                """, UUID.class, tenantId, userId);
        UUID folderId = jdbc.queryForObject("""
                SELECT folder_id FROM mail_folders
                 WHERE tenant_id = ? AND account_id = ? AND folder_type = 'INBOX'
                 ORDER BY folder_id LIMIT 1
                """, UUID.class, tenantId, accountId);
        UUID threadId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID firstRuleId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID secondRuleId = UUID.fromString("00000000-0000-0000-0000-000000000102");

        jdbc.update("""
                UPDATE mail_rules SET lifecycle_state = 'ARCHIVED', enabled = FALSE
                 WHERE tenant_id = ? AND account_id = ?
                """, tenantId, accountId);
        jdbc.update("""
                INSERT INTO mail_threads (
                    thread_id, tenant_id, account_id, folder_id, subject, preview,
                    participants, latest_message_at, unread, starred, importance,
                    triage_lane, workflow_state, message_count, version, created_by, updated_by)
                VALUES (?, ?, ?, ?, 'Backfill project', 'Governed preview', '[]'::jsonb,
                        CURRENT_TIMESTAMP, TRUE, FALSE, 'NORMAL', 'PRIORITY', 'OPEN',
                        1, 0, ?, ?)
                """, threadId, tenantId, accountId, folderId, userId, userId);
        insertRule(jdbc, firstRuleId, tenantId, accountId, userId, true, "MARK_READ");
        insertRule(jdbc, secondRuleId, tenantId, accountId, userId, false, "STAR");

        MailJsonCodec json = new MailJsonCodec(new ObjectMapper().findAndRegisterModules());
        MailOrganizationQueryRepository queries = new MailOrganizationQueryRepository(jdbc, json);
        MailRuleBackfillTransactions transactions = new MailRuleBackfillTransactions(
                queries,
                new MailOrganizationCommandRepository(jdbc, json),
                new MailRuleBackfillRepository(jdbc),
                new MailRuleEvaluator(),
                new MailRuleBackfillFingerprint(),
                new MailCommandRepository(jdbc, json));

        MailRuleBackfillDtos.Preview preview = transaction.execute(
                ignored -> transactions.preview(tenantId, userId, accountId));
        assertThat(preview).isNotNull();
        assertThat(preview.enabledRuleCount()).isEqualTo(2);
        assertThat(preview.matchedThreadCount()).isOne();
        assertThat(preview.plannedApplicationCount()).isOne();

        var request = new MailRuleBackfillDtos.Request(
                UUID.randomUUID(), preview.previewFingerprint());
        MailRuleBackfillRepository.Claim claim = transaction.execute(
                ignored -> transactions.claim(tenantId, userId, accountId, request));
        MailRuleBackfillDtos.Result result = transaction.execute(
                ignored -> transactions.execute(tenantId, userId, "mail-backfill-test", claim, request));

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.applicationCount()).isOne();
        assertThat(result.changedCount()).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT unread FROM mail_threads WHERE thread_id = ?",
                Boolean.class, threadId)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT starred FROM mail_threads WHERE thread_id = ?",
                Boolean.class, threadId)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mail_rule_backfill_applications WHERE execution_id = ?",
                Integer.class, result.executionId())).isOne();

        MailRuleBackfillRepository.Claim replay = transaction.execute(
                ignored -> transactions.claim(tenantId, userId, accountId, request));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.replay().asReplay()).extracting(
                MailRuleBackfillDtos.Result::executionId,
                MailRuleBackfillDtos.Result::replayed)
                .containsExactly(result.executionId(), true);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mail_rule_backfill_applications WHERE execution_id = ?",
                Integer.class, result.executionId())).isOne();

        Long versionAfterChange = jdbc.queryForObject(
                "SELECT version FROM mail_threads WHERE thread_id = ?",
                Long.class, threadId);
        MailRuleBackfillDtos.Preview noChangePreview = transaction.execute(
                ignored -> transactions.preview(tenantId, userId, accountId));
        assertThat(noChangePreview).isNotNull();
        var noChangeRequest = new MailRuleBackfillDtos.Request(
                UUID.randomUUID(), noChangePreview.previewFingerprint());
        MailRuleBackfillRepository.Claim noChangeClaim = transaction.execute(
                ignored -> transactions.claim(tenantId, userId, accountId, noChangeRequest));
        MailRuleBackfillDtos.Result noChangeResult = transaction.execute(
                ignored -> transactions.execute(
                        tenantId, userId, "mail-backfill-no-change-test",
                        noChangeClaim, noChangeRequest));

        assertThat(noChangeResult).isNotNull();
        assertThat(noChangeResult.applicationCount()).isOne();
        assertThat(noChangeResult.changedCount()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT version FROM mail_threads WHERE thread_id = ?",
                Long.class, threadId)).isEqualTo(versionAfterChange);
        assertThat(jdbc.queryForMap("""
                SELECT before_thread_version, after_thread_version, changed
                  FROM mail_rule_backfill_applications
                 WHERE execution_id = ?
                """, noChangeResult.executionId()))
                .containsEntry("before_thread_version", versionAfterChange)
                .containsEntry("after_thread_version", versionAfterChange)
                .containsEntry("changed", false);

        UUID sharedAccountId = jdbc.queryForObject("""
                SELECT account_id FROM mail_accounts
                 WHERE tenant_id = ? AND account_kind = 'SHARED'
                 ORDER BY account_id LIMIT 1
                """, UUID.class, tenantId);
        assertThatThrownBy(() -> transaction.execute(
                ignored -> transactions.preview(tenantId, userId, sharedAccountId)))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void truncatedBackfillIsRejectedBeforeExecutionClaim() {
        String schema = "mail_rule_backfill_truncated";
        Flyway flyway = flyway(schema, null);
        flyway.clean();
        flyway.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        Long tenantId = jdbc.queryForObject("""
                SELECT tenant_id FROM mail_accounts
                 WHERE account_kind = 'PERSONAL' AND owner_user_id IS NOT NULL
                 ORDER BY tenant_id, owner_user_id LIMIT 1
                """, Long.class);
        Long userId = jdbc.queryForObject("""
                SELECT owner_user_id FROM mail_accounts
                 WHERE tenant_id = ? AND account_kind = 'PERSONAL'
                 ORDER BY owner_user_id LIMIT 1
                """, Long.class, tenantId);
        UUID accountId = jdbc.queryForObject("""
                SELECT account_id FROM mail_accounts
                 WHERE tenant_id = ? AND owner_user_id = ? AND account_kind = 'PERSONAL'
                 ORDER BY is_default DESC, account_id LIMIT 1
                """, UUID.class, tenantId, userId);
        UUID folderId = jdbc.queryForObject("""
                SELECT folder_id FROM mail_folders
                 WHERE tenant_id = ? AND account_id = ? AND folder_type = 'INBOX'
                 ORDER BY folder_id LIMIT 1
                """, UUID.class, tenantId, accountId);
        jdbc.update("""
                INSERT INTO mail_threads (
                    thread_id, tenant_id, account_id, folder_id, provider_thread_ref,
                    subject, preview, participants, latest_message_at,
                    unread, importance, triage_lane, workflow_state,
                    message_count, created_by, updated_by)
                SELECT gen_random_uuid(), ?, ?, ?, 'truncated:' || candidate,
                       'Truncated backfill ' || candidate, 'Preview', '[]'::jsonb,
                       CURRENT_TIMESTAMP - candidate * INTERVAL '1 second',
                       TRUE, 'NORMAL', 'PRIORITY', 'OPEN', 1, ?, ?
                  FROM generate_series(1, 501) candidate
                """, tenantId, accountId, folderId, userId, userId);

        MailJsonCodec json = new MailJsonCodec(new ObjectMapper().findAndRegisterModules());
        MailOrganizationQueryRepository queries = new MailOrganizationQueryRepository(jdbc, json);
        MailRuleBackfillTransactions transactions = new MailRuleBackfillTransactions(
                queries,
                new MailOrganizationCommandRepository(jdbc, json),
                new MailRuleBackfillRepository(jdbc),
                new MailRuleEvaluator(),
                new MailRuleBackfillFingerprint(),
                new MailCommandRepository(jdbc, json));
        MailRuleBackfillService service = new MailRuleBackfillService(transactions);
        MailRuleBackfillDtos.Preview preview = service.preview(tenantId, userId, accountId);
        int executionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mail_rule_backfill_executions", Integer.class);

        assertThat(preview.truncated()).isTrue();
        assertThat(preview.scannedCount()).isEqualTo(500);
        assertThatThrownBy(() -> service.run(
                tenantId, userId, accountId, "truncated-backfill",
                new MailRuleBackfillDtos.Request(
                        UUID.randomUUID(), preview.previewFingerprint())))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("truncated");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mail_rule_backfill_executions", Integer.class))
                .isEqualTo(executionCount);
    }

    private void assertLatestSchema(String schema) {
        JdbcTemplate jdbc = jdbc();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.tables
                 WHERE table_schema = ?
                   AND table_name IN (
                       'mail_rules', 'mail_rule_runs',
                       'mail_rule_backfill_executions', 'mail_rule_backfill_applications')
                """, Integer.class, schema)).isEqualTo(4);
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
                  FROM %s.flyway_schema_history
                 WHERE version = '208' AND success
                """.formatted(schema), Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM %s.flyway_schema_history
                 WHERE version = '211' AND success
                """.formatted(schema), Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.columns
                 WHERE table_schema = ?
                   AND ((table_name = 'mail_shared_inbox_members' AND column_name = 'account_id')
                     OR (table_name = 'mail_thread_folders' AND column_name = 'account_id'))
                   AND is_nullable = 'NO'
                """, Integer.class, schema)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_constraint constraint_ref
                  JOIN pg_namespace namespace_ref
                    ON namespace_ref.oid = constraint_ref.connamespace
                 WHERE namespace_ref.nspname = ?
                   AND constraint_ref.conname IN (
                       'fk_mail_folder_tenant_account',
                       'fk_mail_folder_parent_tenant_account',
                       'fk_mail_shared_inbox_tenant_account',
                       'fk_mail_shared_member_tenant_account_inbox',
                       'fk_mail_thread_tenant_account',
                       'fk_mail_thread_tenant_account_folder',
                       'fk_mail_thread_previous_tenant_account_folder',
                       'fk_mail_thread_tenant_account_shared_inbox',
                       'fk_mail_thread_folder_tenant_account_thread',
                       'fk_mail_thread_folder_tenant_account_folder')
                   AND constraint_ref.convalidated
                """, Integer.class, schema)).isEqualTo(10);
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

    private void insertRule(
            JdbcTemplate jdbc,
            UUID ruleId,
            Long tenantId,
            UUID accountId,
            Long userId,
            boolean stopProcessing,
            String action) {
        jdbc.update("""
                INSERT INTO mail_rules (
                    rule_id, tenant_id, account_id, owner_user_id, display_name,
                    priority, match_mode, conditions, actions, stop_processing,
                    enabled, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 10, 'ALL',
                        '[{"field":"SUBJECT","operator":"CONTAINS","value":"Backfill project"}]'::jsonb,
                        jsonb_build_array(jsonb_build_object('type', ?)),
                        ?, TRUE, ?, ?)
                """, ruleId, tenantId, accountId, userId, "Rule " + ruleId,
                action, stopProcessing, userId, userId);
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
