package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalStepUpReplayPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private NamedParameterJdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ApprovalStepUpReplayRepository repository;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repository = new ApprovalStepUpReplayRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
        jdbc.getJdbcTemplate().execute("DROP TABLE IF EXISTS apr_step_up_replay_ledger");
        jdbc.getJdbcTemplate().execute("DROP TABLE IF EXISTS apr_high_risk_idempotency_ledger");
        jdbc.getJdbcTemplate().execute(idempotencyDdl());
        jdbc.getJdbcTemplate().execute(replayDdl());
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void concurrentSameHashReturnsTypedInProgressWithoutAbortingPostgresTransaction()
            throws Exception {
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<ApprovalStepUpReplayRepository.Reservation> first = executor.submit(() ->
                transaction.execute(status -> {
                    ApprovalStepUpReplayRepository.Reservation value = repository.reserve(
                            binding(), routeKey(), "a".repeat(64));
                    reserved.countDown();
                    await(release);
                    return value;
                }));
        assertThat(reserved.await(5, TimeUnit.SECONDS)).isTrue();
        Future<ErrorCode> second = executor.submit(() -> {
            try {
                transaction.execute(status -> repository.reserve(
                        binding(), routeKey(), "a".repeat(64)));
                return null;
            } catch (BaseException exception) {
                return exception.getErrorCode();
            }
        });

        release.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS).committed()).isFalse();
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM apr_high_risk_idempotency_ledger",
                new MapSqlParameterSource(), Long.class)).isEqualTo(1L);
    }

    @Test
    void committedSameHashReturnsOnlyMinimalReceiptAndDifferentHashFailsClosed() throws Exception {
        ApprovalStepUpReplayRepository.Reservation created = transaction.execute(status -> {
            ApprovalStepUpReplayRepository.Reservation value = repository.reserve(
                    binding(), routeKey(), "b".repeat(64));
            repository.commit(value.id(), challenge());
            return value;
        });
        assertThat(created).isNotNull();

        ApprovalStepUpReplayRepository.Reservation prior = transaction.execute(status ->
                repository.reserve(binding(), routeKey(), "b".repeat(64)));

        assertThat(prior).isNotNull();
        assertThat(prior.committed()).isTrue();
        assertThat(prior.receipt()).isEqualTo(new ApprovalStepUpReplayRepository.CommandReceipt(
                1, "WORKFLOW", "14d7b229-4752-4a50-8ac1-ecc129620649", 7, "COMMITTED"));
        String stored = jdbc.queryForObject(
                "SELECT result_receipt::text FROM apr_high_risk_idempotency_ledger",
                new MapSqlParameterSource(), String.class);
        LinkedHashSet<String> receiptFields = new LinkedHashSet<>();
        new ObjectMapper().readTree(stored).fieldNames().forEachRemaining(receiptFields::add);
        assertThat(receiptFields).isEqualTo(Set.of(
                "schemaVersion", "targetType", "targetId", "targetVersion", "status"));

        assertThatThrownBy(() -> transaction.execute(status -> repository.reserve(
                binding(), routeKey(), "c".repeat(64))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
    }

    @Test
    void expiryPurgeDeletesBothLedgersInBoundedBatches() {
        transaction.executeWithoutResult(status -> {
            ApprovalStepUpReplayRepository.Reservation value = repository.reserve(
                    binding(), routeKey(), "d".repeat(64));
            repository.consume(challenge());
            repository.commit(value.id(), challenge());
        });
        jdbc.getJdbcTemplate().update(
                "UPDATE apr_step_up_replay_ledger SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        jdbc.getJdbcTemplate().update(
                "UPDATE apr_high_risk_idempotency_ledger SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");

        ApprovalStepUpReplayRepository.PurgeResult result =
                transaction.execute(status -> repository.purgeExpired(10));

        assertThat(result).isEqualTo(new ApprovalStepUpReplayRepository.PurgeResult(1, 1));
    }

    private ApprovalStepUpVerifier.CommandBinding binding() {
        return new ApprovalStepUpVerifier.CommandBinding(
                17, 42, routeKey(), "approval-management",
                "STEPUP-MGMT-HIGH-V1", "approvals.design.publish", "scope-opaque-1",
                "WORKFLOW", "14d7b229-4752-4a50-8ac1-ecc129620649", 7, "POST",
                "/api/approvals/v1/admin/workflows/14d7b229-4752-4a50-8ac1-ecc129620649/publish",
                "idempotency-1", "e".repeat(64), "psr-" + "f".repeat(64));
    }

    private ApprovalStepUpVerifier.VerifiedChallenge challenge() {
        return new ApprovalStepUpVerifier.VerifiedChallenge(
                "challenge-1", "nonce-1", "https://auth.example.test",
                binding(), Instant.now().plusSeconds(300));
    }

    private String routeKey() {
        return "route.approvals.admin.workflow-publish.action";
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Latch timed out.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private String idempotencyDdl() {
        return """
                CREATE TABLE apr_high_risk_idempotency_ledger (
                    idempotency_id UUID PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    actor_user_id BIGINT NOT NULL,
                    route_contract_key VARCHAR(240) NOT NULL,
                    idempotency_key VARCHAR(200) NOT NULL,
                    request_hash CHAR(64) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    challenge_id VARCHAR(160),
                    result_receipt JSONB,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    committed_at TIMESTAMPTZ,
                    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours'),
                    UNIQUE (tenant_id, actor_user_id, route_contract_key, idempotency_key))
                """;
    }

    private String replayDdl() {
        return """
                CREATE TABLE apr_step_up_replay_ledger (
                    replay_id UUID PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    actor_user_id BIGINT NOT NULL,
                    challenge_id VARCHAR(160) NOT NULL,
                    nonce VARCHAR(160) NOT NULL,
                    activation_policy VARCHAR(120) NOT NULL,
                    capability_contract_key VARCHAR(200) NOT NULL,
                    scope_ref VARCHAR(200) NOT NULL,
                    target_type VARCHAR(80) NOT NULL,
                    target_id VARCHAR(240) NOT NULL,
                    target_version BIGINT NOT NULL,
                    command_method VARCHAR(10) NOT NULL,
                    command_path VARCHAR(1000) NOT NULL,
                    idempotency_key VARCHAR(200) NOT NULL,
                    payload_sha256 CHAR(64) NOT NULL,
                    decision_revision VARCHAR(200) NOT NULL,
                    issuer VARCHAR(500) NOT NULL,
                    consumed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    expires_at TIMESTAMPTZ NOT NULL,
                    UNIQUE (challenge_id, nonce))
                """;
    }
}
