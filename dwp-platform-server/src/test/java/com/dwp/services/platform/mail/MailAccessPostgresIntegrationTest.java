package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.ThreadAction;
import static com.dwp.services.platform.mail.MailTypes.WorkflowState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MailAccessPostgresIntegrationTest {

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void sharedMailboxAccessIsRecheckedForEveryReadAndWrite() {
        String schema = "mail_shared_access_fence";
        migrate(schema);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        MailJsonCodec json = new MailJsonCodec(new ObjectMapper().findAndRegisterModules());
        MailQueryRepository queries = new MailQueryRepository(jdbc, json);
        MailCommandRepository commands = new MailCommandRepository(jdbc, json);
        MailLifecycleRepository lifecycle = new MailLifecycleRepository(jdbc);
        SharedFixture fixture = sharedFixture(jdbc);

        MailDtos.ThreadSummary visible = queries.thread(
                fixture.tenantId(), fixture.userId(), fixture.threadId()).orElseThrow();
        MailLifecycleRepository.LifecycleThread lifecycleVisible = lifecycle.visibleThread(
                fixture.tenantId(), fixture.userId(), fixture.threadId()).orElseThrow();
        assertThat(queries.messages(
                fixture.tenantId(), fixture.userId(), fixture.threadId())).isNotEmpty();
        assertThat(queries.comments(
                fixture.tenantId(), fixture.userId(), fixture.threadId())).isNotEmpty();

        jdbc.update("""
                UPDATE mail_tenant_policies SET allow_shared_inboxes = FALSE
                 WHERE tenant_id = ?
                """, fixture.tenantId());
        assertDenied(queries, commands, lifecycle, fixture, visible, lifecycleVisible);

        jdbc.update("""
                UPDATE mail_tenant_policies SET allow_shared_inboxes = TRUE
                 WHERE tenant_id = ?
                """, fixture.tenantId());
        jdbc.update("""
                UPDATE mail_shared_inboxes SET lifecycle_state = 'ARCHIVED'
                 WHERE tenant_id = ? AND shared_inbox_id = ?
                """, fixture.tenantId(), fixture.sharedInboxId());
        assertDenied(queries, commands, lifecycle, fixture, visible, lifecycleVisible);

        jdbc.update("""
                UPDATE mail_shared_inboxes SET lifecycle_state = 'ACTIVE'
                 WHERE tenant_id = ? AND shared_inbox_id = ?
                """, fixture.tenantId(), fixture.sharedInboxId());
        assertThat(queries.thread(
                fixture.tenantId(), fixture.userId(), fixture.threadId())).isPresent();
        jdbc.update("""
                UPDATE mail_shared_inbox_members SET lifecycle_state = 'RETIRED'
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND user_id = ?
                """, fixture.tenantId(), fixture.sharedInboxId(), fixture.userId());
        assertDenied(queries, commands, lifecycle, fixture, visible, lifecycleVisible);

        UUID otherInboxId = jdbc.queryForObject("""
                SELECT shared_inbox_id FROM mail_shared_inboxes
                 WHERE tenant_id = ? AND account_id <> ?
                 ORDER BY shared_inbox_id LIMIT 1
                """, UUID.class, fixture.tenantId(), fixture.accountId());
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE mail_threads SET shared_inbox_id = ?
                 WHERE tenant_id = ? AND thread_id = ?
                """, otherInboxId, fixture.tenantId(), fixture.threadId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void personalOwnerRemainsAuthorizedAndDueSnoozeReturnsToTheInbox() {
        String schema = "mail_personal_access_and_snooze";
        migrate(schema);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        MailJsonCodec json = new MailJsonCodec(new ObjectMapper().findAndRegisterModules());
        MailQueryRepository queries = new MailQueryRepository(jdbc, json);
        MailCommandRepository commands = new MailCommandRepository(jdbc, json);

        PersonalFixture fixture = personalFixture(jdbc);
        MailDtos.ThreadSummary before = queries.thread(
                fixture.tenantId(), fixture.userId(), fixture.threadIds().get(0)).orElseThrow();
        assertThat(commands.applyAction(
                fixture.tenantId(), fixture.userId(), before.threadId(),
                ThreadAction.STAR, before.version())).isOne();

        UUID futureThreadId = fixture.threadIds().get(0);
        UUID dueThreadId = fixture.threadIds().get(1);
        jdbc.update("""
                UPDATE mail_threads
                   SET workflow_state = 'SNOOZED', snoozed_until = CURRENT_TIMESTAMP + INTERVAL '2 hours'
                 WHERE tenant_id = ? AND thread_id = ?
                """, fixture.tenantId(), futureThreadId);
        jdbc.update("""
                UPDATE mail_threads
                   SET workflow_state = 'SNOOZED', snoozed_until = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                 WHERE tenant_id = ? AND thread_id = ?
                """, fixture.tenantId(), dueThreadId);

        List<MailDtos.ThreadSummary> inbox = queries.threads(
                fixture.tenantId(), fixture.userId(), "", "", "INBOX",
                false, "", 0, 100);
        assertThat(inbox).extracting(MailDtos.ThreadSummary::threadId)
                .doesNotContain(futureThreadId)
                .contains(dueThreadId);
        MailDtos.ThreadSummary due = inbox.stream()
                .filter(thread -> thread.threadId().equals(dueThreadId))
                .findFirst().orElseThrow();
        assertThat(due.workflowState()).isEqualTo(WorkflowState.OPEN);
        assertThat(due.snoozedUntil()).isNull();

        List<MailDtos.ThreadSummary> snoozed = queries.threads(
                fixture.tenantId(), fixture.userId(), "", "SNOOZED", "INBOX",
                false, "", 0, 100);
        assertThat(snoozed).extracting(MailDtos.ThreadSummary::threadId)
                .contains(futureThreadId)
                .doesNotContain(dueThreadId);
        Integer expectedFuture = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE thread.tenant_id = ? AND account.owner_user_id = ?
                   AND thread.workflow_state = 'SNOOZED'
                   AND thread.snoozed_until > CURRENT_TIMESTAMP
                """, Integer.class, fixture.tenantId(), fixture.userId());
        assertThat(queries.metrics(fixture.tenantId(), fixture.userId()).snoozed())
                .isEqualTo(expectedFuture);
    }

    private void assertDenied(
            MailQueryRepository queries,
            MailCommandRepository commands,
            MailLifecycleRepository lifecycle,
            SharedFixture fixture,
            MailDtos.ThreadSummary visible,
            MailLifecycleRepository.LifecycleThread lifecycleVisible) {
        assertThat(queries.thread(
                fixture.tenantId(), fixture.userId(), fixture.threadId())).isEmpty();
        assertThat(queries.messages(
                fixture.tenantId(), fixture.userId(), fixture.threadId())).isEmpty();
        assertThat(queries.comments(
                fixture.tenantId(), fixture.userId(), fixture.threadId())).isEmpty();
        assertThat(lifecycle.visibleThread(
                fixture.tenantId(), fixture.userId(), fixture.threadId())).isEmpty();
        assertThat(lifecycle.target(
                fixture.tenantId(), fixture.userId(), fixture.accountId(),
                lifecycleVisible.folderId())).isEmpty();
        assertThat(queries.isActiveSharedInboxMember(
                fixture.tenantId(), fixture.sharedInboxId(), fixture.userId())).isFalse();
        assertThat(commands.applyAction(
                fixture.tenantId(), fixture.userId(), fixture.threadId(),
                ThreadAction.MARK_READ, visible.version())).isZero();
        assertThat(commands.applyAction(
                fixture.tenantId(), fixture.userId(), fixture.threadId(),
                ThreadAction.ARCHIVE, visible.version())).isZero();
        assertThat(commands.applyAction(
                fixture.tenantId(), fixture.userId(), fixture.threadId(),
                ThreadAction.RESTORE, visible.version())).isZero();
        assertThat(commands.snooze(
                fixture.tenantId(), fixture.userId(), fixture.threadId(),
                OffsetDateTime.now().plusHours(1), visible.version())).isZero();
        assertThat(commands.assign(
                fixture.tenantId(), fixture.userId(), fixture.threadId(),
                fixture.userId(), "Revoked member", visible.version())).isZero();
        assertThat(commands.insertComment(
                fixture.tenantId(), fixture.userId(), "Revoked member",
                fixture.threadId(), "Must not persist", List.of())).isNull();
        assertThat(commands.insertReply(
                fixture.tenantId(), fixture.userId(), fixture.threadId(),
                "Must not persist", UUID.randomUUID())).isFalse();
    }

    private SharedFixture sharedFixture(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT thread.tenant_id, membership.user_id, thread.thread_id,
                       thread.account_id, thread.shared_inbox_id
                  FROM mail_threads thread
                  JOIN mail_shared_inboxes inbox
                    ON inbox.tenant_id = thread.tenant_id
                   AND inbox.account_id = thread.account_id
                   AND inbox.shared_inbox_id = thread.shared_inbox_id
                  JOIN mail_shared_inbox_members membership
                    ON membership.tenant_id = inbox.tenant_id
                   AND membership.shared_inbox_id = inbox.shared_inbox_id
                   AND membership.lifecycle_state = 'ACTIVE'
                 WHERE inbox.lifecycle_state = 'ACTIVE'
                   AND EXISTS (SELECT 1 FROM mail_messages message
                                WHERE message.tenant_id = thread.tenant_id
                                  AND message.thread_id = thread.thread_id)
                   AND EXISTS (SELECT 1 FROM mail_internal_comments comment
                                WHERE comment.tenant_id = thread.tenant_id
                                  AND comment.thread_id = thread.thread_id)
                 ORDER BY thread.thread_id, membership.user_id
                 LIMIT 1
                """, result -> {
            if (!result.next()) throw new IllegalStateException("Shared mail fixture is missing.");
            return new SharedFixture(
                    result.getLong("tenant_id"), result.getLong("user_id"),
                    result.getObject("thread_id", UUID.class),
                    result.getObject("account_id", UUID.class),
                    result.getObject("shared_inbox_id", UUID.class));
        });
    }

    private PersonalFixture personalFixture(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT account.tenant_id, account.owner_user_id, thread.thread_id
                  FROM mail_accounts account
                  JOIN mail_threads thread
                    ON thread.tenant_id = account.tenant_id
                   AND thread.account_id = account.account_id
                  JOIN mail_folders folder
                    ON folder.tenant_id = thread.tenant_id
                   AND folder.account_id = thread.account_id
                   AND folder.folder_id = thread.folder_id
                 WHERE account.account_kind = 'PERSONAL'
                   AND folder.folder_type = 'INBOX'
                 ORDER BY account.tenant_id, account.owner_user_id, thread.thread_id
                """, result -> {
            if (!result.next()) throw new IllegalStateException("Personal mail fixture is missing.");
            long tenantId = result.getLong("tenant_id");
            long userId = result.getLong("owner_user_id");
            java.util.ArrayList<UUID> threadIds = new java.util.ArrayList<>();
            threadIds.add(result.getObject("thread_id", UUID.class));
            while (result.next() && result.getLong("tenant_id") == tenantId
                    && result.getLong("owner_user_id") == userId && threadIds.size() < 2) {
                threadIds.add(result.getObject("thread_id", UUID.class));
            }
            if (threadIds.size() < 2) throw new IllegalStateException("Two personal threads are required.");
            return new PersonalFixture(tenantId, userId, List.copyOf(threadIds));
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

    private record SharedFixture(
            Long tenantId, Long userId, UUID threadId, UUID accountId, UUID sharedInboxId) {
    }

    private record PersonalFixture(Long tenantId, Long userId, List<UUID> threadIds) {
    }
}
