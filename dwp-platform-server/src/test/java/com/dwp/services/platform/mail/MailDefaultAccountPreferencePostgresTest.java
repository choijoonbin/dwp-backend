package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.Classification.INTERNAL;
import static com.dwp.services.platform.mail.MailTypes.DeliveryMode.SEND;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MailDefaultAccountPreferencePostgresTest {

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void storedPreferenceOverridesStaticDefaultAcrossEveryComposeEntryPoint() {
        String schema = "mail_default_account_preference";
        migrate(schema);
        PGSimpleDataSource source = dataSource(schema);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(source));
        MailJsonCodec json = new MailJsonCodec(
                new ObjectMapper().findAndRegisterModules());
        Owner owner = owner(jdbc);
        OffsetDateTime lastSync = OffsetDateTime.parse("2026-09-17T01:02:03Z");
        jdbc.update("""
                UPDATE mail_provider_connections connection
                   SET last_synchronized_at = ?
                  FROM mail_accounts account
                 WHERE account.tenant_id = connection.tenant_id
                   AND account.connection_id = connection.connection_id
                   AND account.tenant_id = ? AND account.account_id = ?
                """, lastSync, owner.tenantId(), owner.staticDefaultAccountId());
        UUID preferredAccountId = addPreferredAccount(jdbc, owner);
        setPreference(jdbc, owner, preferredAccountId);

        MailQueryRepository queries = new MailQueryRepository(jdbc, json);
        List<MailDtos.AccountSummary> accounts =
                queries.accounts(owner.tenantId(), owner.userId());
        assertThat(accounts)
                .filteredOn(MailDtos.AccountSummary::defaultAccount)
                .extracting(MailDtos.AccountSummary::accountId)
                .containsExactly(preferredAccountId);
        assertThat(accounts)
                .filteredOn(account -> account.accountId().equals(owner.staticDefaultAccountId()))
                .singleElement()
                .extracting(MailDtos.AccountSummary::defaultAccount)
                .isEqualTo(false);
        MailDtos.AccountReadiness persistedEvidence = accounts.stream()
                .filter(account -> account.accountId().equals(owner.staticDefaultAccountId()))
                .findFirst().orElseThrow().readiness();
        assertThat(persistedEvidence.lastSuccessfulSyncAt()).isEqualTo(lastSync);
        assertThat(persistedEvidence.lastSuccessfulSyncScope()).isEqualTo("ACCOUNT");
        assertThat(persistedEvidence.featureReadiness().values())
                .allSatisfy(feature -> {
                    assertThat(feature.lastSuccessfulAt()).isNull();
                    assertThat(feature.lastSuccessfulScope()).isEqualTo("UNAVAILABLE");
                });

        MailWorkspaceRepository workspace = new MailWorkspaceRepository(jdbc, json);
        assertThat(workspace.composeAccount(owner.tenantId(), owner.userId(), null))
                .contains(preferredAccountId);
        assertThat(workspace.composeAccount(
                owner.tenantId(), owner.userId(), owner.staticDefaultAccountId()))
                .contains(owner.staticDefaultAccountId());

        MailCommandRepository commands = new MailCommandRepository(jdbc, json);
        MailCommandRepository.ComposeResult composed = commands.compose(
                owner.tenantId(), owner.userId(),
                new MailDtos.ComposeRequest(
                        "basic-recipient@example.com", "Basic Recipient",
                        "Preferred basic compose", "Basic body", SEND, UUID.randomUUID()),
                "a".repeat(64));
        assertThreadAccount(jdbc, owner.tenantId(), composed.threadId(), preferredAccountId);

        MailDraftRepository drafts = new MailDraftRepository(jdbc);
        MailDraftRepository.CreateResult draft = drafts.create(
                owner.tenantId(), owner.userId(),
                new MailDtos.DraftSaveRequest(
                        "draft-recipient@example.com", "Draft Recipient",
                        "Preferred draft", "Draft body", UUID.randomUUID(), null));
        assertThat(draft).isNotNull();
        assertThreadAccount(jdbc, owner.tenantId(), draft.threadId(), preferredAccountId);

        MailAddressBookRepository addressBook = new MailAddressBookRepository(jdbc);
        UUID groupId = addressBook.createGroup(
                owner.tenantId(), owner.userId(),
                new MailAddressBookDtos.ContactGroupCreateRequest(
                        "Preferred account group", "Regression group", UUID.randomUUID()));
        MailGroupComposeRepository groupCompose = new MailGroupComposeRepository(jdbc, json);
        MailGroupComposeRepository.ComposeResult group = transaction.execute(status ->
                groupCompose.compose(
                        owner.tenantId(), owner.userId(), groupId,
                        new MailAddressBookDtos.GroupMessageRequest(
                                "Preferred group compose", "Group body", INTERNAL,
                                MailAddressBookDtos.GroupRecipientMode.TO,
                                UUID.randomUUID(), 0L),
                        List.of(new MailAddressBookRepository.Recipient(
                                UUID.randomUUID(), "Group Recipient",
                                "group-recipient@example.com")),
                        "corr-default-account", "a".repeat(64)));
        assertThat(group).isNotNull();
        assertThreadAccount(jdbc, owner.tenantId(), group.threadId(), preferredAccountId);

        SharedAccount shared = sharedAccount(jdbc, owner);
        jdbc.update("""
                UPDATE mail_shared_inbox_access_grants
                   SET can_read = TRUE, can_send_as = FALSE,
                       can_send_on_behalf = TRUE
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND user_id = ?
                """, owner.tenantId(), shared.sharedInboxId(), owner.userId());
        setPreference(jdbc, owner, shared.accountId());
        assertThat(workspace.accountSendAccessible(
                owner.tenantId(), owner.userId(), shared.accountId())).isTrue();
        assertThat(workspace.composeAccount(owner.tenantId(), owner.userId(), null))
                .contains(shared.accountId());

        jdbc.update("""
                UPDATE mail_shared_inbox_access_grants
                   SET can_send_as = FALSE, can_send_on_behalf = FALSE
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND user_id = ?
                """, owner.tenantId(), shared.sharedInboxId(), owner.userId());
        assertThat(workspace.accountAccessible(
                owner.tenantId(), owner.userId(), shared.accountId())).isTrue();
        assertThat(workspace.accountSendAccessible(
                owner.tenantId(), owner.userId(), shared.accountId())).isFalse();
        assertThat(workspace.composeAccount(owner.tenantId(), owner.userId(), null)).isEmpty();
    }

    private UUID addPreferredAccount(JdbcTemplate jdbc, Owner owner) {
        UUID accountId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mail_accounts (
                    account_id, tenant_id, connection_id, owner_user_id,
                    email_address, display_name, account_kind,
                    connection_state, synchronization_state, provider_account_ref,
                    is_default, created_by, updated_by)
                SELECT ?, tenant_id, connection_id, owner_user_id,
                       ?, 'Preferred account', 'PERSONAL',
                       'ACTIVE', 'READY', ?, FALSE, ?, ?
                  FROM mail_accounts
                 WHERE tenant_id = ? AND account_id = ?
                """, accountId,
                "preferred-" + owner.userId() + "@example.com",
                "preference-test:" + accountId,
                owner.userId(), owner.userId(), owner.tenantId(),
                owner.staticDefaultAccountId());
        jdbc.update("""
                INSERT INTO mail_folders (
                    folder_id, tenant_id, account_id, provider_folder_ref,
                    folder_key, display_name, folder_type, sort_order)
                SELECT gen_random_uuid(), tenant_id, ?,
                       'preference-test:' || folder_key,
                       folder_key, display_name, folder_type, sort_order
                  FROM mail_folders
                 WHERE tenant_id = ? AND account_id = ?
                   AND folder_type IN ('SENT', 'DRAFTS')
                """, accountId, owner.tenantId(), owner.staticDefaultAccountId());
        return accountId;
    }

    private void setPreference(JdbcTemplate jdbc, Owner owner, UUID accountId) {
        jdbc.update("""
                INSERT INTO mail_user_preferences (
                    tenant_id, user_id, default_account_id, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_id) DO UPDATE
                    SET default_account_id = EXCLUDED.default_account_id,
                        version = mail_user_preferences.version + 1,
                        updated_at = CURRENT_TIMESTAMP,
                        updated_by = EXCLUDED.updated_by
                """, owner.tenantId(), owner.userId(), accountId,
                owner.userId(), owner.userId());
    }

    private void assertThreadAccount(
            JdbcTemplate jdbc, long tenantId, UUID threadId, UUID expectedAccountId) {
        assertThat(jdbc.queryForObject("""
                SELECT account_id
                  FROM mail_threads
                 WHERE tenant_id = ? AND thread_id = ?
                """, UUID.class, tenantId, threadId)).isEqualTo(expectedAccountId);
    }

    private Owner owner(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT tenant_id, owner_user_id, account_id
                  FROM mail_accounts
                 WHERE account_kind = 'PERSONAL'
                   AND owner_user_id IS NOT NULL
                   AND is_default = TRUE
                   AND connection_state = 'ACTIVE'
                   AND EXISTS (
                       SELECT 1
                         FROM mail_shared_inbox_access_grants access_grant
                         JOIN mail_shared_inboxes inbox
                           ON inbox.tenant_id = access_grant.tenant_id
                          AND inbox.shared_inbox_id = access_grant.shared_inbox_id
                          AND inbox.lifecycle_state = 'ACTIVE'
                        WHERE access_grant.tenant_id = mail_accounts.tenant_id
                          AND access_grant.user_id = mail_accounts.owner_user_id
                          AND access_grant.member_state = 'ACTIVE')
                 ORDER BY tenant_id, owner_user_id
                 LIMIT 1
                """, result -> {
            if (!result.next()) {
                throw new IllegalStateException("Personal mail fixture is missing.");
            }
            return new Owner(
                    result.getLong("tenant_id"),
                    result.getLong("owner_user_id"),
                    result.getObject("account_id", UUID.class));
        });
    }

    private SharedAccount sharedAccount(JdbcTemplate jdbc, Owner owner) {
        return jdbc.queryForObject("""
                SELECT account.account_id, inbox.shared_inbox_id
                  FROM mail_accounts account
                  JOIN mail_shared_inboxes inbox
                    ON inbox.tenant_id = account.tenant_id
                   AND inbox.account_id = account.account_id
                   AND inbox.lifecycle_state = 'ACTIVE'
                  JOIN mail_shared_inbox_access_grants access_grant
                    ON access_grant.tenant_id = inbox.tenant_id
                   AND access_grant.shared_inbox_id = inbox.shared_inbox_id
                   AND access_grant.user_id = ?
                   AND access_grant.member_state = 'ACTIVE'
                 WHERE account.tenant_id = ?
                   AND account.account_kind = 'SHARED'
                   AND account.connection_state = 'ACTIVE'
                 ORDER BY account.account_id
                 LIMIT 1
                """, (result, ignored) -> new SharedAccount(
                        result.getObject("account_id", UUID.class),
                        result.getObject("shared_inbox_id", UUID.class)),
                owner.userId(), owner.tenantId());
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

    private record Owner(
            long tenantId,
            long userId,
            UUID staticDefaultAccountId) {
    }

    private record SharedAccount(UUID accountId, UUID sharedInboxId) {
    }
}
