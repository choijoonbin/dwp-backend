package com.dwp.services.platform.mail;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventOutboxRepository;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.services.platform.mail.MailTypes.ProposalDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static com.dwp.services.platform.mail.AdminMailWritingAssetDtos.*;

@Testcontainers(disabledWithoutDocker = true)
class MailFollowUpDueNotificationPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static JdbcTemplate jdbc;
    static TransactionTemplate transaction;
    static MailFollowUpDueNotificationTransactions notifications;

    @BeforeAll
    static void migratedNotificationPublisher() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(source)
                .locations("filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        DomainEventContractRegistry contracts = new DomainEventContractRegistry();
        MailNotificationPreferenceRepository preferences =
                new MailNotificationPreferenceRepository(jdbc);
        DomainEventRecorder recorder = new DomainEventRecorder(
                new DomainEventOutboxRepository(new NamedParameterJdbcTemplate(source), json),
                contracts,
                json);
        notifications = new MailFollowUpDueNotificationTransactions(
                new MailFollowUpDueNotificationRepository(jdbc),
                new MailNotificationEvents(recorder, contracts, json, preferences));
    }

    @Test
    void versionedDueOccurrenceIsEmittedOrSuppressedExactlyOnce() {
        Owner owner = owner();
        UUID followUpId = UUID.randomUUID();
        OffsetDateTime firstDue = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);
        jdbc.update("""
                INSERT INTO mail_follow_up_trackers (
                    follow_up_id, tenant_id, owner_user_id, thread_id,
                    expected_reply_at, time_zone, note, tracker_status,
                    version, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'UTC', 'Integration verification',
                        'WAITING', 0, ?, ?)
                """, followUpId, owner.tenantId(), owner.userId(), owner.threadId(),
                firstDue, owner.userId(), owner.userId());

        OffsetDateTime firstCheck = OffsetDateTime.now(ZoneOffset.UTC);
        var first = transaction.execute(status -> notifications.publishDue(firstCheck, 10));
        var replay = transaction.execute(status -> notifications.publishDue(firstCheck, 10));

        assertThat(first).isEqualTo(
                new MailFollowUpDueNotificationTransactions.BatchResult(1, 1, 0));
        assertThat(replay).isEqualTo(
                new MailFollowUpDueNotificationTransactions.BatchResult(0, 0, 0));
        assertThat(ledger(followUpId)).containsEntry("decision", "EMITTED");
        assertThat(ledger(followUpId).get("domain_event_id")).isNotNull();
        assertThat(outboxCount(followUpId)).isOne();
        assertThat(trackerStatus(followUpId)).isEqualTo("OVERDUE");

        jdbc.update("""
                INSERT INTO mail_user_preferences (
                    tenant_id, user_id, notify_follow_up_due, created_by, updated_by)
                VALUES (?, ?, FALSE, ?, ?)
                ON CONFLICT (tenant_id, user_id) DO UPDATE
                   SET notify_follow_up_due = FALSE,
                       version = mail_user_preferences.version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = EXCLUDED.updated_by
                """, owner.tenantId(), owner.userId(), owner.userId(), owner.userId());
        OffsetDateTime secondDue = firstDue.plusMinutes(5);
        jdbc.update("""
                UPDATE mail_follow_up_trackers
                   SET expected_reply_at = ?, tracker_status = 'WAITING',
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND follow_up_id = ?
                """, secondDue, owner.tenantId(), followUpId);

        OffsetDateTime secondCheck = firstCheck.plusMinutes(10);
        var suppressed = transaction.execute(status -> notifications.publishDue(secondCheck, 10));
        var suppressedReplay =
                transaction.execute(status -> notifications.publishDue(secondCheck, 10));

        assertThat(suppressed).isEqualTo(
                new MailFollowUpDueNotificationTransactions.BatchResult(1, 0, 1));
        assertThat(suppressedReplay).isEqualTo(
                new MailFollowUpDueNotificationTransactions.BatchResult(0, 0, 0));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM mail_follow_up_notification_ledger
                 WHERE tenant_id = ? AND follow_up_id = ?
                """, Integer.class, owner.tenantId(), followUpId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM mail_follow_up_notification_ledger
                 WHERE tenant_id = ? AND follow_up_id = ? AND decision = 'SUPPRESSED'
                   AND domain_event_id IS NULL AND completed_at IS NOT NULL
                """, Integer.class, owner.tenantId(), followUpId)).isOne();
        assertThat(outboxCount(followUpId)).isOne();
    }

    @Test
    void organizationWritingAssetIsInvisibleUntilSeparatedApprovalAndPublication() {
        Owner owner = owner();
        AdminMailWritingAssetRepository admin =
                new AdminMailWritingAssetRepository(jdbc);
        MailWorkspaceRepository user = new MailWorkspaceRepository(
                jdbc, new MailJsonCodec(new ObjectMapper().findAndRegisterModules()));
        DraftRequest request = new DraftRequest(
                "Approved response", "{{recipientName}} request", "Hello {{recipientName}}",
                MailWorkspaceDtos.BodyFormat.TEXT, "Company confidential notice",
                false, false, null, null);

        OrganizationAsset draft = admin.createDraft(
                owner.tenantId(), owner.userId(), AssetKind.TEMPLATE, request);
        assertThat(user.templates(owner.tenantId(), owner.userId(), false))
                .noneMatch(item -> item.templateId().equals(draft.assetId()));

        OrganizationAsset submitted = admin.transition(
                        owner.tenantId(), owner.userId(), AssetKind.TEMPLATE, draft.assetId(),
                        PublicationState.DRAFT, PublicationState.PENDING_APPROVAL,
                        draft.version())
                .orElseThrow();
        OrganizationAsset approved = admin.transition(
                        owner.tenantId(), owner.userId() + 1, AssetKind.TEMPLATE, draft.assetId(),
                        PublicationState.PENDING_APPROVAL, PublicationState.APPROVED,
                        submitted.version())
                .orElseThrow();
        OrganizationAsset published = admin.transition(
                        owner.tenantId(), owner.userId() + 1, AssetKind.TEMPLATE, draft.assetId(),
                        PublicationState.APPROVED, PublicationState.PUBLISHED,
                        approved.version())
                .orElseThrow();

        assertThat(published.publicationVersion()).isOne();
        assertThat(user.templates(owner.tenantId(), owner.userId(), false))
                .anyMatch(item -> item.templateId().equals(draft.assetId())
                        && !item.editable()
                        && "Company confidential notice".equals(item.mandatoryContent()));
    }

    @Test
    void acceptedProposalCreatesStableCommandAndProjectsOwnerOutcome() {
        Owner owner = owner();
        UUID proposalId = UUID.randomUUID();
        UUID accountId = jdbc.queryForObject(
                "SELECT account_id FROM mail_threads WHERE tenant_id = ? AND thread_id = ?",
                UUID.class, owner.tenantId(), owner.threadId());
        jdbc.update("""
                INSERT INTO mail_action_proposals (
                    proposal_id, tenant_id, account_id, thread_id, proposal_type,
                    proposal_status, title, summary, evidence, proposed_payload,
                    confidence, risk_level, required_resource_key,
                    required_permission_code, target_route, expires_at,
                    action_contract_version, created_by, updated_by)
                VALUES (?, ?, ?, ?, 'DRAFT_REPLY', 'PROPOSED', 'Reply proposal',
                        'Review and send a reply', '[{"messageId":"source"}]'::jsonb,
                        '{"tone":"formal","language":"ko","requiresConfirmation":true}'::jsonb,
                        0.9500, 'LOW', 'APP.MAIL', 'CREATE', '/mail/inbox',
                        CURRENT_TIMESTAMP + INTERVAL '1 day', 1, ?, ?)
                """, proposalId, owner.tenantId(), accountId, owner.threadId(),
                owner.userId(), owner.userId());
        MailJsonCodec json = new MailJsonCodec(new ObjectMapper().findAndRegisterModules());
        MailCommandRepository commands = new MailCommandRepository(jdbc, json);
        MailQueryRepository queries = new MailQueryRepository(jdbc, json);

        assertThat(commands.decideProposal(
                owner.tenantId(), owner.userId(), proposalId,
                ProposalDecision.ACCEPT, 0L)).isOne();
        var accepted = queries.proposalHandoff(
                owner.tenantId(), owner.userId(), proposalId).orElseThrow();
        assertThat(accepted.commandId()).isNotNull();
        assertThat(accepted.ownerState()).isEqualTo("ACCEPTED");

        assertThat(commands.updateProposalOutcome(
                owner.tenantId(), owner.userId(), proposalId, accepted.commandId(),
                "EXECUTED", "mail:draft:result", accepted.version())).isOne();
        var executed = queries.proposalHandoff(
                owner.tenantId(), owner.userId(), proposalId).orElseThrow();
        assertThat(executed.commandId()).isEqualTo(accepted.commandId());
        assertThat(executed.ownerState()).isEqualTo("EXECUTED");
        assertThat(executed.resultRef()).isEqualTo("mail:draft:result");
    }

    private Owner owner() {
        return jdbc.query("""
                SELECT account.tenant_id, account.owner_user_id, thread.thread_id
                  FROM mail_accounts account
                  JOIN mail_threads thread
                    ON thread.tenant_id = account.tenant_id
                   AND thread.account_id = account.account_id
                 WHERE account.account_kind = 'PERSONAL'
                   AND account.owner_user_id IS NOT NULL
                 ORDER BY account.tenant_id, account.owner_user_id, thread.thread_id
                 LIMIT 1
                """, result -> {
            if (!result.next()) throw new IllegalStateException("Mail owner fixture is missing.");
            return new Owner(
                    result.getLong("tenant_id"),
                    result.getLong("owner_user_id"),
                    result.getObject("thread_id", UUID.class));
        });
    }

    private Map<String, Object> ledger(UUID followUpId) {
        return jdbc.queryForMap("""
                SELECT decision, domain_event_id
                  FROM mail_follow_up_notification_ledger
                 WHERE follow_up_id = ?
                 ORDER BY claimed_at
                 LIMIT 1
                """, followUpId);
    }

    private int outboxCount(UUID followUpId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM sys_domain_event_outbox
                 WHERE event_source = 'urn:dwp:platform:mail'
                   AND event_type = 'mail.follow-up.due.v1'
                   AND aggregate_id = ?
                """, Integer.class, followUpId.toString());
    }

    private String trackerStatus(UUID followUpId) {
        return jdbc.queryForObject("""
                SELECT tracker_status FROM mail_follow_up_trackers WHERE follow_up_id = ?
                """, String.class, followUpId);
    }

    private record Owner(long tenantId, long userId, UUID threadId) { }
}
