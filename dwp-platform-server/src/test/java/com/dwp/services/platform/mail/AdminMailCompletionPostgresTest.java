package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailWorkspaceDtos.PurgeApprovalRequest;
import static com.dwp.services.platform.mail.MailWorkspaceDtos.PurgeExecuteRequest;
import static com.dwp.services.platform.mail.MailWorkspaceDtos.PurgePreviewRequest;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AdminMailCompletionPostgresTest {

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void actionGrantIsRecheckedAlongsideLegacyMembership() {
        String schema = "mail_admin_access_matrix";
        migrate(schema);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        MailQueryRepository queries = new MailQueryRepository(
                jdbc, new MailJsonCodec(new ObjectMapper().findAndRegisterModules()));
        AccessFixture fixture = accessFixture(jdbc);

        assertThat(queries.thread(
                fixture.tenantId(), fixture.userId(), fixture.threadId())).isPresent();
        jdbc.update("""
                UPDATE mail_shared_inbox_access_grants
                   SET can_read = TRUE, can_send_as = FALSE,
                       can_send_on_behalf = FALSE, can_assign = FALSE, can_manage = FALSE,
                       expires_at = NULL, member_state = 'ACTIVE'
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND user_id = ?
                """, fixture.tenantId(), fixture.inboxId(), fixture.userId());

        assertThat(queries.hasSharedInboxPermission(
                fixture.tenantId(), fixture.inboxId(), fixture.userId(),
                MailQueryRepository.SharedInboxPermission.READ)).isTrue();
        assertThat(queries.hasSharedInboxPermission(
                fixture.tenantId(), fixture.inboxId(), fixture.userId(),
                MailQueryRepository.SharedInboxPermission.SEND)).isFalse();

        jdbc.update("""
                UPDATE mail_shared_inbox_access_grants SET can_send_on_behalf = TRUE
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND user_id = ?
                """, fixture.tenantId(), fixture.inboxId(), fixture.userId());
        assertThat(queries.hasSharedInboxPermission(
                fixture.tenantId(), fixture.inboxId(), fixture.userId(),
                MailQueryRepository.SharedInboxPermission.SEND)).isTrue();

        jdbc.update("""
                UPDATE mail_shared_inbox_access_grants SET can_read = FALSE
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND user_id = ?
                """, fixture.tenantId(), fixture.inboxId(), fixture.userId());
        assertThat(queries.thread(
                fixture.tenantId(), fixture.userId(), fixture.threadId())).isEmpty();
    }

    @Test
    void purgeDeletesOnlyAfterFreshFingerprintAndTwoDistinctApprovals() {
        String schema = "mail_admin_purge_execution";
        migrate(schema);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AdminMailCompletionRepository repository =
                new AdminMailCompletionRepository(jdbc, objectMapper);
        AdminMailCompletionService service = new AdminMailCompletionService(
                repository,
                new MailConnectorRegistry(List.of(new DwpSandboxMailConnector())),
                objectMapper);
        PurgeFixture fixture = purgeFixture(jdbc);
        long policyVersion = jdbc.queryForObject("""
                SELECT version FROM mail_tenant_policies WHERE tenant_id = ?
                """, Long.class, fixture.tenantId());

        var preview = service.previewPurge(
                fixture.tenantId(), 7001,
                new PurgePreviewRequest(
                        Map.of("tenant", true), List.of("THREADS", "MESSAGES"),
                        OffsetDateTime.now(ZoneOffset.UTC), UUID.randomUUID(), policyVersion));
        assertThat(preview.eligibleCount()).isGreaterThanOrEqualTo(2);

        service.approvePurge(
                fixture.tenantId(), 7002, preview.candidateSnapshotId(),
                new PurgeApprovalRequest("APPROVE", UUID.randomUUID(), policyVersion));
        var second = service.approvePurge(
                fixture.tenantId(), 7003, preview.candidateSnapshotId(),
                new PurgeApprovalRequest("APPROVE", UUID.randomUUID(), policyVersion));
        assertThat(second.distinctApproverCount()).isEqualTo(2);

        var job = service.executePurge(
                fixture.tenantId(), 7001, preview.candidateSnapshotId(), "purge-correlation",
                new PurgeExecuteRequest(
                        UUID.randomUUID(), policyVersion, preview.fingerprint()));

        assertThat(job.state()).isEqualTo("SUCCEEDED");
        assertThat(job.verificationState()).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM mail_threads
                 WHERE tenant_id = ? AND thread_id = ?
                """, Integer.class, fixture.tenantId(), fixture.threadId())).isZero();
    }

    private AccessFixture accessFixture(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT thread.tenant_id, member.user_id, thread.thread_id,
                       thread.shared_inbox_id
                  FROM mail_threads thread
                  JOIN mail_shared_inbox_members member
                    ON member.tenant_id = thread.tenant_id
                   AND member.shared_inbox_id = thread.shared_inbox_id
                   AND member.lifecycle_state = 'ACTIVE'
                  JOIN mail_shared_inbox_access_grants access_grant
                    ON access_grant.tenant_id = member.tenant_id
                   AND access_grant.shared_inbox_id = member.shared_inbox_id
                   AND access_grant.user_id = member.user_id
                 WHERE thread.shared_inbox_id IS NOT NULL
                 ORDER BY thread.thread_id, member.user_id LIMIT 1
                """, result -> {
            if (!result.next()) throw new IllegalStateException("Shared mail fixture missing");
            return new AccessFixture(
                    result.getLong("tenant_id"), result.getLong("user_id"),
                    result.getObject("thread_id", UUID.class),
                    result.getObject("shared_inbox_id", UUID.class));
        });
    }

    private PurgeFixture purgeFixture(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT account.tenant_id, account.account_id, folder.folder_id
                  FROM mail_accounts account
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                   AND connection.provider_type = 'DWP_SANDBOX'
                  JOIN mail_folders folder
                    ON folder.tenant_id = account.tenant_id
                   AND folder.account_id = account.account_id
                   AND folder.lifecycle_state = 'ACTIVE'
                 WHERE account.account_kind = 'PERSONAL'
                 ORDER BY account.tenant_id, account.account_id, folder.sort_order
                 LIMIT 1
                """, result -> {
            if (!result.next()) throw new IllegalStateException("Sandbox account fixture missing");
            long tenantId = result.getLong("tenant_id");
            UUID accountId = result.getObject("account_id", UUID.class);
            UUID folderId = result.getObject("folder_id", UUID.class);
            UUID threadId = jdbc.queryForObject("""
                    INSERT INTO mail_threads (
                        tenant_id, account_id, folder_id, subject, preview, participants,
                        latest_message_at, workflow_state, trashed_at, message_count, created_by,
                        updated_by, updated_at)
                    VALUES (?, ?, ?, 'Purge fixture', 'Purge fixture', '[]'::jsonb,
                            CURRENT_TIMESTAMP - INTERVAL '500 days', 'TRASHED',
                            CURRENT_TIMESTAMP - INTERVAL '500 days', 1, 7001,
                            7001, CURRENT_TIMESTAMP - INTERVAL '500 days')
                    RETURNING thread_id
                    """, UUID.class, tenantId, accountId, folderId);
            jdbc.update("""
                    INSERT INTO mail_messages (
                        tenant_id, thread_id, sender_email, sender_name, recipients,
                        message_direction, body_format, body_content, sent_at, created_by)
                    VALUES (?, ?, 'purge@example.test', 'Purge fixture', '[]'::jsonb,
                            'INBOUND', 'TEXT', 'Purge fixture body',
                            CURRENT_TIMESTAMP - INTERVAL '500 days', 7001)
                    """, tenantId, threadId);
            return new PurgeFixture(tenantId, threadId);
        });
    }

    private void migrate(String schema) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource())
                .schemas(schema)
                .defaultSchema(schema)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }

    private PGSimpleDataSource dataSource(String schema) {
        PGSimpleDataSource source = dataSource();
        source.setCurrentSchema(schema);
        return source;
    }

    private record AccessFixture(long tenantId, long userId, UUID threadId, UUID inboxId) { }
    private record PurgeFixture(long tenantId, UUID threadId) { }
}
