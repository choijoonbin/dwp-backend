package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalQueryRepository;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalOwnerPredicatePostgresTest {

    private static final UUID TASK_ID = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");
    private static final UUID WORKFLOW_VERSION_ID = UUID.fromString(
            "30000000-0000-0000-0000-000000000001");
    private static final UUID WORKFLOW_ID = UUID.fromString(
            "40000000-0000-0000-0000-000000000001");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private NamedParameterJdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ApprovalIdentityDirectory identities;
    private ApprovalOwnerPredicateEvaluator evaluator;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        identities = mock(ApprovalIdentityDirectory.class);
        evaluator = new ApprovalOwnerPredicateEvaluator(jdbc, identities);
        createSchema();
        seedDelegatedTask();
        when(identities.require(42, 100)).thenReturn(delegator(List.of("APPROVER")));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void locksTheCurrentDelegationBeforeAConcurrentRevocationCanCommit() throws Exception {
        CountDownLatch validated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Void> guarded = executor.submit(() -> transaction.execute(status -> {
            evaluator.lockClaimableTask(actor(), staleExpected(false), 1);
            validated.countDown();
            await(release);
            return null;
        }));
        assertThat(validated.await(5, TimeUnit.SECONDS)).isTrue();
        Future<Integer> revocation = executor.submit(() -> transaction.execute(status ->
                jdbc.getJdbcTemplate().update(
                        "UPDATE apr_delegations SET lifecycle_state='REVOKED' "
                                + "WHERE tenant_id=42 AND delegate_user_id=200")));

        assertThatThrownBy(() -> revocation.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        release.countDown();

        assertThat(guarded.get(5, TimeUnit.SECONDS)).isNull();
        assertThat(revocation.get(5, TimeUnit.SECONDS)).isEqualTo(1);
    }

    @Test
    void ignoresStaleDelegatedFlagsAndFailsClosedAfterDelegationRevocation() {
        jdbc.getJdbcTemplate().update(
                "UPDATE apr_delegations SET lifecycle_state='REVOKED' WHERE tenant_id=42");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                evaluator.lockClaimableTask(actor(), staleExpected(true), 1)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_NOT_AVAILABLE));
    }

    @Test
    void rechecksTheDelegatorsCurrentIdentityRoleWhileHoldingTheRows() {
        when(identities.require(42, 100)).thenReturn(delegator(List.of()));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                evaluator.lockClaimableTask(actor(), staleExpected(true), 1)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_NOT_AVAILABLE));
    }

    private ApprovalRequestContext.Actor actor() {
        return new ApprovalRequestContext.Actor(200L, 42L, null, "Delegate", Set.of(), Set.of());
    }

    private ApprovalIdentityDirectory.Subject delegator(List<String> roles) {
        return new ApprovalIdentityDirectory.Subject(
                42L, 100L, null, null, "Delegator", "delegator@example.test", null,
                "ACTIVE", roles);
    }

    private ApprovalQueryRepository.TaskAccess staleExpected(boolean delegated) {
        ApprovalDtos.TaskSummary summary = new ApprovalDtos.TaskSummary(
                TASK_ID, REQUEST_ID, "APR-1", "Title", "Summary", "Workflow", "Workflow",
                "review", "Review", 1, "Requester", "Org", "PENDING", "NORMAL",
                "INTERNAL", 0, Instant.now(), Instant.now().plusSeconds(3600), 1);
        return new ApprovalQueryRepository.TaskAccess(
                summary, 300, null, "APPROVER", delegated, delegated ? 999L : null);
    }

    private void createSchema() {
        jdbc.getJdbcTemplate().execute("DROP TABLE IF EXISTS apr_delegations");
        jdbc.getJdbcTemplate().execute("DROP TABLE IF EXISTS apr_tasks");
        jdbc.getJdbcTemplate().execute("DROP TABLE IF EXISTS apr_requests");
        jdbc.getJdbcTemplate().execute("DROP TABLE IF EXISTS apr_workflow_versions");
        jdbc.getJdbcTemplate().execute("DROP TABLE IF EXISTS apr_workflow_definitions");
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE apr_workflow_definitions (
                    tenant_id BIGINT NOT NULL, workflow_id UUID NOT NULL,
                    workflow_key VARCHAR(100) NOT NULL,
                    PRIMARY KEY (tenant_id, workflow_id))
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE apr_workflow_versions (
                    tenant_id BIGINT NOT NULL, workflow_version_id UUID NOT NULL,
                    workflow_id UUID NOT NULL,
                    PRIMARY KEY (tenant_id, workflow_version_id))
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE apr_requests (
                    tenant_id BIGINT NOT NULL, request_id UUID NOT NULL,
                    requester_user_id BIGINT NOT NULL, workflow_version_id UUID NOT NULL,
                    PRIMARY KEY (tenant_id, request_id))
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE apr_tasks (
                    tenant_id BIGINT NOT NULL, task_id UUID NOT NULL, request_id UUID NOT NULL,
                    version BIGINT NOT NULL, status VARCHAR(20) NOT NULL,
                    assignee_user_id BIGINT, candidate_role VARCHAR(100),
                    PRIMARY KEY (tenant_id, task_id))
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TABLE apr_delegations (
                    delegation_id UUID PRIMARY KEY, tenant_id BIGINT NOT NULL,
                    delegator_user_id BIGINT NOT NULL, delegate_user_id BIGINT NOT NULL,
                    scope_type VARCHAR(24) NOT NULL, workflow_id UUID,
                    workflow_key VARCHAR(100),
                    starts_at TIMESTAMPTZ NOT NULL, ends_at TIMESTAMPTZ NOT NULL,
                    lifecycle_state VARCHAR(20) NOT NULL,
                    delegated_role_codes JSONB NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """);
    }

    private void seedDelegatedTask() {
        jdbc.getJdbcTemplate().update(
                "INSERT INTO apr_workflow_definitions VALUES (42, ?, 'expense')", WORKFLOW_ID);
        jdbc.getJdbcTemplate().update(
                "INSERT INTO apr_workflow_versions VALUES (42, ?, ?)",
                WORKFLOW_VERSION_ID, WORKFLOW_ID);
        jdbc.getJdbcTemplate().update(
                "INSERT INTO apr_requests VALUES (42, ?, 300, ?)",
                REQUEST_ID, WORKFLOW_VERSION_ID);
        jdbc.getJdbcTemplate().update(
                "INSERT INTO apr_tasks VALUES (42, ?, ?, 1, 'PENDING', NULL, 'APPROVER')",
                TASK_ID, REQUEST_ID);
        jdbc.getJdbcTemplate().update("""
                INSERT INTO apr_delegations (
                    delegation_id, tenant_id, delegator_user_id, delegate_user_id,
                    scope_type, workflow_id, workflow_key,
                    starts_at, ends_at, lifecycle_state,
                    delegated_role_codes)
                VALUES (?, 42, 100, 200, 'WORKFLOW', ?, 'expense',
                        CURRENT_TIMESTAMP - INTERVAL '1 hour',
                        CURRENT_TIMESTAMP + INTERVAL '1 hour', 'ACTIVE', '["APPROVER"]')
                """, UUID.fromString("50000000-0000-0000-0000-000000000001"),
                WORKFLOW_ID);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Latch timed out.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
