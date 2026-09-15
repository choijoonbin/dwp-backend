package com.dwp.services.approval.domain;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalDelegationConcurrencyPostgresTest {

    private static final long TENANT = 42L;
    private static final long USER_A = 101L;
    private static final long USER_B = 202L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private ApprovalCommandRepository commands;

    @BeforeEach
    void setUp() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        new JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        new JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        commands = new ApprovalCommandRepository(
                new NamedParameterJdbcTemplate(dataSource),
                objectMapper);
        jdbc.update("INSERT INTO apr_tenants (tenant_id) VALUES (?)", TENANT);
    }

    @Test
    void canonicalPairLockAllowsOnlyOneOfConcurrentOppositeDelegations() throws Exception {
        Instant startsAt = Instant.now().plusSeconds(30);
        Instant endsAt = startsAt.plus(Duration.ofDays(7));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentCreate(
                    actor(USER_A), subject(USER_B), startsAt, endsAt, ready, start));
            var second = executor.submit(() -> concurrentCreate(
                    actor(USER_B), subject(USER_A), startsAt, endsAt, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(Arrays.asList(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(null, ErrorCode.RESOURCE_CONFLICT);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM apr_delegations WHERE tenant_id = ?",
                Integer.class, TENANT)).isEqualTo(1);
    }

    @Test
    void reverseDelegationIsRejectedOnlyWhileItsWindowOverlaps() {
        Instant startsAt = Instant.now().plusSeconds(30);
        Instant endsAt = startsAt.plus(Duration.ofDays(7));
        create(actor(USER_A), subject(USER_B), startsAt, endsAt);

        assertThatThrownBy(() -> create(
                actor(USER_B), subject(USER_A), startsAt.plusSeconds(1), endsAt.plusSeconds(1)))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        create(actor(USER_B), subject(USER_A), endsAt, endsAt.plus(Duration.ofDays(2)));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM apr_delegations WHERE tenant_id = ?",
                Integer.class, TENANT)).isEqualTo(2);
    }

    @Test
    void versionedUpdateIsOwnerBoundLimitedToNinetyDaysAndDurablyReplayable() {
        Instant startsAt = Instant.now().minusSeconds(30);
        var created = create(actor(USER_A), subject(USER_B), startsAt,
                startsAt.plus(Duration.ofDays(7)));
        var request = update(USER_B, startsAt, startsAt.plus(Duration.ofDays(30)), 0,
                "Extend delegation after the monthly close review");

        TransactionTemplate transaction = transaction();
        transaction.executeWithoutResult(ignored -> {
            var replay = commands.delegationUpdateReplay(
                    actor(USER_A), created.delegationId(), request, "delegation-update-1");
            assertThat(replay.replayed()).isFalse();
            var updated = commands.updateDelegation(
                    actor(USER_A), created.delegationId(), request, subject(USER_B), replay);
            recordUpdateReceipt(
                    actor(USER_A), created.delegationId(), request, replay, updated);
        });

        assertThat(jdbc.queryForObject(
                "SELECT version FROM apr_delegations WHERE delegation_id = ?",
                Long.class, created.delegationId())).isEqualTo(1L);
        transaction.executeWithoutResult(ignored -> assertThat(commands.delegationUpdateReplay(
                actor(USER_A), created.delegationId(), request,
                "delegation-update-1").replayed()).isTrue());

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                commands.delegationUpdateReplay(actor(USER_A), created.delegationId(),
                        update(USER_B, startsAt, startsAt.plus(Duration.ofDays(31)), 0,
                                "A different command must not reuse the same key"),
                        "delegation-update-1")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        assertThatThrownBy(() -> updateInTransaction(
                actor(USER_A), created.delegationId(),
                update(USER_B, startsAt, startsAt.plus(Duration.ofDays(91)), 1,
                        "Ninety one days exceeds the delegation ceiling"),
                "delegation-update-91-days"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        assertThatThrownBy(() -> updateInTransaction(
                actor(303), created.delegationId(),
                update(USER_B, startsAt, startsAt.plus(Duration.ofDays(20)), 1,
                        "Another user cannot edit this delegation"),
                "delegation-update-foreign-owner"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_AVAILABLE));
    }

    @Test
    void updateCannotMoveAnExistingWindowAcrossAnOppositeDelegation() {
        Instant now = Instant.now().plusSeconds(30);
        var first = create(actor(USER_A), subject(USER_B), now, now.plus(Duration.ofDays(2)));
        create(actor(USER_B), subject(USER_A), now.plus(Duration.ofDays(3)),
                now.plus(Duration.ofDays(5)));

        assertThatThrownBy(() -> updateInTransaction(
                actor(USER_A), first.delegationId(),
                update(USER_B, now, now.plus(Duration.ofDays(4)), 0,
                        "Extending into a reverse grant must be rejected"),
                "delegation-update-overlap"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void updateAcceptsTheExactNinetyDayBoundary() {
        Instant startsAt = Instant.now().plusSeconds(30);
        var created = create(actor(USER_A), subject(USER_B), startsAt,
                startsAt.plus(Duration.ofDays(7)));

        updateInTransaction(
                actor(USER_A), created.delegationId(),
                update(USER_B, startsAt, startsAt.plus(Duration.ofDays(90)), 0,
                        "Use the exact maximum delegation period"),
                "delegation-update-exact-90-days");

        assertThat(jdbc.queryForObject(
                "SELECT version FROM apr_delegations WHERE delegation_id = ?",
                Long.class, created.delegationId())).isEqualTo(1L);
    }

    @Test
    void optimisticVersionAllowsOnlyOneConcurrentUpdate() throws Exception {
        Instant startsAt = Instant.now().plusSeconds(30);
        var created = create(actor(USER_A), subject(USER_B), startsAt,
                startsAt.plus(Duration.ofDays(7)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentUpdate(
                    created.delegationId(), startsAt, startsAt.plus(Duration.ofDays(8)),
                    "First concurrent delegation update", "delegation-update-concurrent-a",
                    ready, start));
            var second = executor.submit(() -> concurrentUpdate(
                    created.delegationId(), startsAt, startsAt.plus(Duration.ofDays(9)),
                    "Second concurrent delegation update", "delegation-update-concurrent-b",
                    ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(Arrays.asList(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(null, ErrorCode.OBJECT_VERSION_CONFLICT);
        }
        assertThat(jdbc.queryForObject(
                "SELECT version FROM apr_delegations WHERE delegation_id = ?",
                Long.class, created.delegationId())).isEqualTo(1L);
    }

    @Test
    void createPreservesSelfTenantAndDuplicateInvariants() {
        Instant startsAt = Instant.now().plusSeconds(30);
        Instant endsAt = startsAt.plus(Duration.ofDays(7));

        assertThatThrownBy(() -> create(actor(USER_A), subject(USER_A), startsAt, endsAt))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        ApprovalIdentityDirectory.Subject foreign = new ApprovalIdentityDirectory.Subject(
                99L, USER_B, UUID.randomUUID(), UUID.randomUUID(), "Foreign User",
                "foreign@example.test", "Approver", "ACTIVE", List.of("FINANCE_APPROVERS"));
        assertThatThrownBy(() -> create(actor(USER_A), foreign, startsAt, endsAt))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        create(actor(USER_A), subject(USER_B), startsAt, endsAt);
        assertThatThrownBy(() -> create(
                actor(USER_A), subject(USER_B), startsAt.plusSeconds(1), endsAt.plusSeconds(1)))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM apr_delegations WHERE tenant_id = ?",
                Integer.class, TENANT)).isEqualTo(1);
    }

    private ErrorCode concurrentCreate(
            ApprovalRequestContext.Actor actor,
            ApprovalIdentityDirectory.Subject delegate,
            Instant startsAt,
            Instant endsAt,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
        try {
            transaction().executeWithoutResult(ignored -> create(actor, delegate, startsAt, endsAt));
            return null;
        } catch (BaseException exception) {
            return exception.getErrorCode();
        }
    }

    private ErrorCode concurrentUpdate(
            UUID delegationId,
            Instant startsAt,
            Instant endsAt,
            String reason,
            String key,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
        try {
            updateInTransaction(
                    actor(USER_A), delegationId, update(USER_B, startsAt, endsAt, 0, reason), key);
            return null;
        } catch (BaseException exception) {
            return exception.getErrorCode();
        }
    }

    private ApprovalDelegationCommandSupport.Created create(
            ApprovalRequestContext.Actor actor,
            ApprovalIdentityDirectory.Subject delegate,
            Instant startsAt,
            Instant endsAt) {
        return commands.createDelegation(actor, new ApprovalDtos.CreateDelegationRequest(
                delegate.userId(), "ALL", null, null, startsAt, endsAt,
                "Delegate approval work during an approved absence"), delegate);
    }

    private void updateInTransaction(
            ApprovalRequestContext.Actor actor,
            UUID delegationId,
            ApprovalDelegationUpdateRequest request,
            String key) {
        transaction().executeWithoutResult(ignored -> {
            var replay = commands.delegationUpdateReplay(actor, delegationId, request, key);
            commands.updateDelegation(actor, delegationId, request,
                    subject(request.delegateUserId()), replay);
        });
    }

    private void recordUpdateReceipt(
            ApprovalRequestContext.Actor actor,
            UUID delegationId,
            ApprovalDelegationUpdateRequest request,
            ApprovalDelegationCommandSupport.UpdateReplay replay,
            ApprovalDelegationCommandSupport.Updated updated) {
        new AuditOutboxRecorder(
                new NamedParameterJdbcTemplate(dataSource), objectMapper,
                "dwp-approval-server", "test", "test")
                .record(AuditEvent.builder()
                        .eventId(replay.eventId())
                        .tenantId(actor.tenantId())
                        .category("ADMIN_CHANGE")
                        .action("approval.delegation.updated")
                        .actorType("USER")
                        .actorId(actor.userId().toString())
                        .actorRoles(List.copyOf(actor.roles()))
                        .sourceService("dwp-approval-server")
                        .sourceModule("approval-decision-hub")
                        .targetType("APPROVAL_DELEGATION")
                        .targetId(delegationId.toString())
                        .afterState(updated.auditAfterState(request))
                        .retentionClass("EXTENDED")
                        .build());
    }

    private ApprovalDelegationUpdateRequest update(
            long delegateUserId,
            Instant startsAt,
            Instant endsAt,
            long expectedVersion,
            String reason) {
        return new ApprovalDelegationUpdateRequest(
                delegateUserId, "ALL", null, startsAt, endsAt, reason, expectedVersion);
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private ApprovalRequestContext.Actor actor(long userId) {
        return new ApprovalRequestContext.Actor(
                userId, TENANT, UUID.randomUUID(), "User " + userId,
                Set.of("FINANCE_APPROVERS"), Set.of());
    }

    private ApprovalIdentityDirectory.Subject subject(long userId) {
        return new ApprovalIdentityDirectory.Subject(
                TENANT, userId, UUID.randomUUID(), UUID.randomUUID(),
                "User " + userId, "user" + userId + "@example.test",
                "Approver", "ACTIVE", List.of("FINANCE_APPROVERS"));
    }
}
