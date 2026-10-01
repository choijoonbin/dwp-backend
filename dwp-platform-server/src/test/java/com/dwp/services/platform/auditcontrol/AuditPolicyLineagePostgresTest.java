package com.dwp.services.platform.auditcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class AuditPolicyLineagePostgresTest {

    private static final long TENANT_ID = 9_102L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void serializesDraftSnapshotAgainstPublishAndRejectsStaleApprovedLineage() throws Exception {
        PGSimpleDataSource source = dataSource();
        Flyway flyway = Flyway.configure()
                .dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        AuditControlRepository repository = new AuditControlRepository(
                new NamedParameterJdbcTemplate(source),
                new ObjectMapper().findAndRegisterModules());
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(source);
        AuditControlDtos.RetentionPolicy baseline = repository.policy(TENANT_ID);
        UUID nextRevisionId = transaction(transactionManager).execute(status -> {
            AuditControlDtos.RetentionPolicy locked =
                    repository.lockPolicyForRevisionDraft(TENANT_ID);
            AuditControlDtos.PolicyRevisionCreate request = request(730, 50_000);
            return repository.createPolicyRevision(
                    TENANT_ID, "first-author", request, locked.activeRevisionId(), null,
                    Map.of("standardRetentionDays", Map.of("before", 365, "after", 730)),
                    "1".repeat(64), repository.policyImpactSnapshot(
                            TENANT_ID, locked, request, Instant.now()));
        });
        AuditControlDtos.PolicyRevision approvedNext = approve(
                repository, nextRevisionId, "first-author", "first-reviewer");

        CountDownLatch draftLockAcquired = new CountDownLatch(1);
        CountDownLatch allowDraftInsert = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> staleDraft = executor.submit(() ->
                    transaction(transactionManager).execute(status -> {
                        AuditControlDtos.RetentionPolicy locked =
                                repository.lockPolicyForRevisionDraft(TENANT_ID);
                        draftLockAcquired.countDown();
                        await(allowDraftInsert);
                        AuditControlDtos.PolicyRevisionCreate request = request(365, 25_000);
                        return repository.createPolicyRevision(
                                TENANT_ID, "second-author", request,
                                locked.activeRevisionId(), null,
                                Map.of("exportLimitRows", Map.of(
                                        "before", locked.exportLimitRows(), "after", 25_000)),
                                "2".repeat(64), repository.policyImpactSnapshot(
                                        TENANT_ID, locked, request, Instant.now()));
                    }));
            assertThat(draftLockAcquired.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> concurrentPublish = executor.submit(() ->
                    transaction(transactionManager).execute(status ->
                            repository.publishPolicyRevision(
                                    TENANT_ID, nextRevisionId, "publisher",
                                    approvedNext.version())));
            assertThatThrownBy(() -> concurrentPublish.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowDraftInsert.countDown();
            UUID staleRevisionId = staleDraft.get(10, TimeUnit.SECONDS);
            assertThat(concurrentPublish.get(10, TimeUnit.SECONDS)).isTrue();

            AuditControlDtos.PolicyRevision stale = repository
                    .policyRevision(TENANT_ID, staleRevisionId)
                    .orElseThrow();
            assertThat(stale.baselineRevisionId()).isEqualTo(baseline.activeRevisionId());
            assertThat(stale.diff()).containsOnlyKeys("exportLimitRows");
            assertThat(repository.policy(TENANT_ID).activeRevisionId())
                    .isEqualTo(nextRevisionId);

            AuditControlDtos.PolicyRevision staleApproved = approve(
                    repository, staleRevisionId, "second-author", "second-reviewer");
            Boolean published = transaction(transactionManager).execute(status ->
                    repository.publishPolicyRevision(
                            TENANT_ID, staleRevisionId, "second-publisher",
                            staleApproved.version()));
            assertThat(published).isFalse();
            assertThat(repository.policy(TENANT_ID).activeRevisionId())
                    .isEqualTo(nextRevisionId);
            assertThat(repository.policyRevisions(TENANT_ID, 1))
                    .extracting(AuditControlDtos.PolicyRevision::revisionId)
                    .containsExactly(staleRevisionId);
            assertThat(repository.policyRevision(
                    TENANT_ID, baseline.activeRevisionId()))
                    .get()
                    .extracting(AuditControlDtos.PolicyRevision::revisionId)
                    .isEqualTo(baseline.activeRevisionId());
        } finally {
            allowDraftInsert.countDown();
            executor.shutdownNow();
        }
    }

    private AuditControlDtos.PolicyRevision approve(
            AuditControlRepository repository,
            UUID revisionId,
            String author,
            String reviewer) {
        AuditControlDtos.PolicyRevision draft = repository
                .policyRevision(TENANT_ID, revisionId)
                .orElseThrow();
        assertThat(repository.submitPolicyRevision(
                TENANT_ID, revisionId, author, draft.version())).isTrue();
        AuditControlDtos.PolicyApproval approval = repository
                .policyRevision(TENANT_ID, revisionId)
                .orElseThrow()
                .approval();
        assertThat(repository.decidePolicyRevision(
                TENANT_ID, revisionId, approval.approvalId(), reviewer,
                "APPROVED", "Independent review completed.", approval.version())).isTrue();
        return repository.policyRevision(TENANT_ID, revisionId).orElseThrow();
    }

    private AuditControlDtos.PolicyRevisionCreate request(int standardDays, int exportLimit) {
        return new AuditControlDtos.PolicyRevisionCreate(
                standardDays, 2_555, exportLimit, true, true, 70,
                "Exercise serialized policy lineage controls.", null);
    }

    private TransactionTemplate transaction(DataSourceTransactionManager manager) {
        return new TransactionTemplate(manager);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the concurrent policy operation.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating policy operations.", exception);
        }
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }
}
