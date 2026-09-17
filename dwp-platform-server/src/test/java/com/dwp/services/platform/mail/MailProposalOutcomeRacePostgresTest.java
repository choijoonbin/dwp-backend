package com.dwp.services.platform.mail;

import com.dwp.services.platform.mail.MailTypes.ProposalDecision;
import com.dwp.services.platform.mail.MailTypes.ProposalType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MailProposalOutcomeRacePostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static MailCommandRepository commands;
    private static MailQueryRepository queries;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        MailJsonCodec json = new MailJsonCodec(
                new ObjectMapper().findAndRegisterModules());
        commands = new MailCommandRepository(jdbc, json);
        queries = new MailQueryRepository(jdbc, json);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
    }

    @Test
    void committedOwnerReservationWinsAgainstAWaitingUserCancellation()
            throws Exception {
        AcceptedProposal accepted = acceptedProposal();
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> reserve = executor.submit(() -> transactions.execute(status -> {
                int changed = commands.reserveProposalExecution(
                        accepted.tenantId(), accepted.actorId(), accepted.proposalId(),
                        accepted.commandId(), ProposalType.CREATE_LEAVE_REQUEST,
                        accepted.version());
                reserved.countDown();
                await(allowCommit);
                return changed;
            }));
            assertThat(reserved.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> cancel = executor.submit(() -> transactions.execute(status ->
                    commands.cancelProposalOutcome(
                            accepted.tenantId(), accepted.actorId(), accepted.proposalId(),
                            accepted.commandId(), accepted.version(),
                            "cancelled-by-user:" + accepted.actorId())));

            assertThatThrownBy(() -> cancel.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            allowCommit.countDown();

            assertThat(reserve.get(10, TimeUnit.SECONDS)).isOne();
            assertThat(cancel.get(10, TimeUnit.SECONDS)).isZero();
        }
        assertThat(state(accepted.proposalId())).isEqualTo("EXECUTING");
    }

    @Test
    void committedUserCancellationWinsAgainstAWaitingOwnerReservation()
            throws Exception {
        AcceptedProposal accepted = acceptedProposal();
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> cancel = executor.submit(() -> transactions.execute(status -> {
                int changed = commands.cancelProposalOutcome(
                        accepted.tenantId(), accepted.actorId(), accepted.proposalId(),
                        accepted.commandId(), accepted.version(),
                        "cancelled-by-user:" + accepted.actorId());
                cancelled.countDown();
                await(allowCommit);
                return changed;
            }));
            assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> reserve = executor.submit(() -> transactions.execute(status ->
                    commands.reserveProposalExecution(
                            accepted.tenantId(), accepted.actorId(), accepted.proposalId(),
                            accepted.commandId(), ProposalType.CREATE_LEAVE_REQUEST,
                            accepted.version())));

            assertThatThrownBy(() -> reserve.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            allowCommit.countDown();

            assertThat(cancel.get(10, TimeUnit.SECONDS)).isOne();
            assertThat(reserve.get(10, TimeUnit.SECONDS)).isZero();
        }
        assertThat(state(accepted.proposalId())).isEqualTo("CANCELLED");
    }

    private AcceptedProposal acceptedProposal() {
        Owner owner = owner();
        UUID accountId = jdbc.queryForObject("""
                SELECT account_id FROM mail_threads
                 WHERE tenant_id = ? AND thread_id = ?
                """, UUID.class, owner.tenantId(), owner.threadId());
        UUID proposalId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mail_action_proposals (
                    proposal_id, tenant_id, account_id, thread_id, proposal_type,
                    proposal_status, title, summary, evidence, proposed_payload,
                    confidence, risk_level, required_resource_key,
                    required_permission_code, target_route, expires_at,
                    action_contract_version, created_by, updated_by)
                VALUES (?, ?, ?, ?, 'CREATE_LEAVE_REQUEST', 'PROPOSED',
                        'Leave request proposal', 'Create the reviewed leave request',
                        '[{"messageId":"source"}]'::jsonb,
                        '{"durationDays":1,"requiresConfirmation":true}'::jsonb,
                        0.9500, 'MEDIUM', 'APP.PEOPLE', 'CREATE', '/people/absence',
                        CURRENT_TIMESTAMP + INTERVAL '1 day', 1, ?, ?)
                """, proposalId, owner.tenantId(), accountId, owner.threadId(),
                owner.actorId(), owner.actorId());
        assertThat(commands.decideProposal(
                owner.tenantId(), owner.actorId(), proposalId,
                ProposalDecision.ACCEPT, 0L)).isOne();
        MailQueryRepository.ProposalHandoffRow accepted = queries.proposalHandoff(
                owner.tenantId(), owner.actorId(), proposalId).orElseThrow();
        return new AcceptedProposal(
                owner.tenantId(), owner.actorId(), proposalId,
                accepted.commandId(), accepted.version());
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
            if (!result.next()) throw new IllegalStateException(
                    "Mail owner fixture is missing.");
            return new Owner(
                    result.getLong("tenant_id"),
                    result.getLong("owner_user_id"),
                    result.getObject("thread_id", UUID.class));
        });
    }

    private String state(UUID proposalId) {
        return jdbc.queryForObject("""
                SELECT owner_state FROM mail_action_proposals WHERE proposal_id = ?
                """, String.class, proposalId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the proposal race test.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the proposal race test.", exception);
        }
    }

    private record Owner(long tenantId, long actorId, UUID threadId) {
    }

    private record AcceptedProposal(
            long tenantId,
            long actorId,
            UUID proposalId,
            UUID commandId,
            long version) {
    }
}
