package com.dwp.services.space.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.space.security.SpaceRequestContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SpaceMembershipConcurrencyPostgresTest extends SpaceMembershipPostgresFixture {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Override
    PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void concurrentOwnerRemovalsSerializeAndPreserveAnEffectiveOwner(Mutation mutation) throws Exception {
        // These fixtures are committed so separate JDBC transactions can see the same two owners.
        UUID spaceId = transaction.execute(status -> {
            UUID id = space(TENANT, "HIDDEN");
            member(TENANT, id, "USER", "first-owner", "OWNER", MembershipWindow.INDEFINITE);
            member(TENANT, id, "USER", "second-owner", "OWNER", MembershipWindow.INDEFINITE);
            return id;
        });
        List<UUID> ownerIds = jdbc.queryForList("""
                SELECT membership_id FROM spc_memberships WHERE space_id = ? ORDER BY principal_ref
                """, UUID.class, spaceId);
        CountDownLatch firstMutationApplied = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        CountDownLatch secondTransactionStarted = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger secondPid = new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> first = null;
        Future<ErrorCode> second = null;
        try {
            first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '5s'");
                mutate(mutation, spaceId, ownerIds.get(0), "first-owner");
                firstMutationApplied.countDown();
                await(allowFirstCommit);
            }));
            assertThat(firstMutationApplied.await(5, TimeUnit.SECONDS)).as("first mutation applied").isTrue();

            second = executor.submit(() -> {
                try {
                    transaction.executeWithoutResult(status -> {
                        jdbc.execute("SET LOCAL lock_timeout = '5s'");
                        secondPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        secondTransactionStarted.countDown();
                        mutate(mutation, spaceId, ownerIds.get(1), "second-owner");
                    });
                    return null;
                } catch (BaseException rejected) {
                    return rejected.getErrorCode();
                }
            });
            assertThat(secondTransactionStarted.await(5, TimeUnit.SECONDS)).as("second transaction started").isTrue();
            // Keep the first removal uncommitted until PostgreSQL itself reports the second writer
            // waiting on a lock. Without the Space lock, the second guard sees the old owner and
            // can remove its own different row, leaving no effective owner after both commits.
            assertThat(waitingOnDatabaseLock(secondPid.get(), second))
                    .as("second owner mutation must wait for the first Space transaction")
                    .isTrue();
            allowFirstCommit.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
            assertThat(queries.hasActiveOwner(TENANT, spaceId)).isTrue();
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM spc_memberships
                     WHERE tenant_id = ? AND space_id = ? AND member_role = 'OWNER'
                       AND lifecycle_state = 'ACTIVE' AND valid_from <= CURRENT_TIMESTAMP
                       AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
                    """, Integer.class, TENANT, spaceId)).isOne();
        } finally {
            allowFirstCommit.countDown();
            if (first != null) first.cancel(true);
            if (second != null) second.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("JDBC workers stopped").isTrue();
            transaction.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM spc_memberships WHERE tenant_id = ? AND space_id = ?", TENANT, spaceId);
                jdbc.update("DELETE FROM spc_spaces WHERE tenant_id = ? AND space_id = ?", TENANT, spaceId);
            });
        }
    }

    @ParameterizedTest
    @EnumSource(GuardedWrite.class)
    void revocationWhileWaitingForSpaceLockRejectsThePreviouslyAuthorizedWrite(GuardedWrite write) throws Exception {
        var actor = subject(TENANT);
        var keeper = new SpaceRequestContext.Subject(actor.userId() + 1, TENANT, null,
                "Remaining owner", actor.roles(), actor.permissions(), actor.groupRefs());
        String originalRole = write == GuardedWrite.CONTENT ? "CONTRIBUTOR" : "OWNER";
        AuthorizationFixture fixture = transaction.execute(status -> {
            UUID spaceId = space(TENANT, "OPEN");
            UUID actorMembership = member(TENANT, spaceId, "USER", Long.toString(actor.userId()),
                    originalRole, MembershipWindow.INDEFINITE);
            member(TENANT, spaceId, "USER", Long.toString(keeper.userId()), "OWNER", MembershipWindow.INDEFINITE);
            UUID target = member(TENANT, spaceId, "USER", "update-target", "VIEWER", MembershipWindow.INDEFINITE);
            UUID request = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO spc_access_requests (access_request_id, tenant_id, space_id,
                        requester_user_id, justification)
                    VALUES (?, ?, ?, ?, 'Authorization race regression')
                    """, request, TENANT, spaceId, actor.userId() + 10);
            return new AuthorizationFixture(spaceId, actorMembership, target, request);
        });
        SpaceService service = service();
        CountDownLatch revocationApplied = new CountDownLatch(1);
        CountDownLatch allowRevocationCommit = new CountDownLatch(1);
        CountDownLatch writerStarted = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger writerPid = new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> revocation = null;
        Future<ErrorCode> attemptedWrite = null;
        try {
            revocation = executor.submit(() -> transaction.executeWithoutResult(status -> {
                commands.revokeMember(keeper, fixture.spaceId(), fixture.actorMembership());
                revocationApplied.countDown();
                await(allowRevocationCommit);
            }));
            assertThat(revocationApplied.await(5, TimeUnit.SECONDS)).as("revocation is uncommitted").isTrue();
            attemptedWrite = executor.submit(() -> {
                try {
                    transaction.executeWithoutResult(status -> {
                        jdbc.execute("SET LOCAL lock_timeout = '5s'");
                        writerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        SpaceRequestContext.set(actor);
                        try {
                            // The first read must still see the old committed role. Only a fresh
                            // read after the blocking Space lock can observe the revocation.
                            assertThat(queries.space(actor, key(fixture.spaceId())).memberRole()).isEqualTo(originalRole);
                            writerStarted.countDown();
                            guardedWrite(service, write, fixture);
                        } finally {
                            SpaceRequestContext.clear();
                        }
                    });
                    return null;
                } catch (BaseException rejected) {
                    return rejected.getErrorCode();
                }
            });
            assertThat(writerStarted.await(5, TimeUnit.SECONDS)).as("writer sees its original role").isTrue();
            assertThat(waitingOnDatabaseLock(writerPid.get(), attemptedWrite))
                    .as("service write waits on the Space lock held by revocation").isTrue();
            allowRevocationCommit.countDown();
            revocation.get(10, TimeUnit.SECONDS);
            assertThat(attemptedWrite.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorCode.FORBIDDEN);

            assertThat(jdbc.queryForMap("SELECT lifecycle_state, version FROM spc_memberships WHERE membership_id = ?",
                    fixture.actorMembership())).isEqualTo(Map.of("lifecycle_state", "REVOKED", "version", 1L));
            assertThat(jdbc.queryForMap("SELECT member_role, version FROM spc_memberships WHERE membership_id = ?",
                    fixture.targetMembership())).isEqualTo(Map.of("member_role", "VIEWER", "version", 0L));
            assertThat(jdbc.queryForMap("SELECT status, version FROM spc_access_requests WHERE access_request_id = ?",
                    fixture.requestId())).isEqualTo(Map.of("status", "PENDING", "version", 0L));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spc_memberships WHERE space_id = ?",
                    Integer.class, fixture.spaceId())).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spc_content_items WHERE space_id = ?",
                    Integer.class, fixture.spaceId())).isZero();
            assertThat(jdbc.queryForMap("SELECT content_policy, version FROM spc_spaces WHERE space_id = ?",
                    fixture.spaceId())).isEqualTo(Map.of("content_policy", "OWNER_REVIEW", "version", 0L));
            assertThat(queries.canManage(actor, fixture.spaceId())).isFalse();
            assertThat(queries.hasActiveOwner(TENANT, fixture.spaceId())).isTrue();
        } finally {
            allowRevocationCommit.countDown();
            if (revocation != null) revocation.cancel(true);
            if (attemptedWrite != null) attemptedWrite.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("authorization workers stopped").isTrue();
            transaction.executeWithoutResult(status -> {
                UUID id = fixture.spaceId();
                jdbc.update("DELETE FROM spc_publication_reviews WHERE space_id = ?", id);
                jdbc.update("""
                        DELETE FROM spc_content_revisions WHERE content_id IN
                            (SELECT content_id FROM spc_content_items WHERE space_id = ?)
                        """, id);
                jdbc.update("DELETE FROM spc_content_items WHERE space_id = ?", id);
                jdbc.update("DELETE FROM spc_activity_events WHERE space_id = ?", id);
                jdbc.update("DELETE FROM spc_access_requests WHERE space_id = ?", id);
                jdbc.update("DELETE FROM spc_memberships WHERE space_id = ?", id);
                jdbc.update("DELETE FROM spc_spaces WHERE space_id = ?", id);
            });
        }
    }

    private void guardedWrite(SpaceService service, GuardedWrite write, AuthorizationFixture fixture) {
        String spaceKey = key(fixture.spaceId());
        switch (write) {
            case SAVE -> service.saveMember(spaceKey,
                    new SpaceDtos.SaveMemberRequest("USER", Long.toString(subject(TENANT).userId()), "OWNER", null),
                    "revoked-manager-save");
            case UPDATE -> service.updateMember(spaceKey, fixture.targetMembership(),
                    new SpaceDtos.UpdateMemberRequest("EDITOR", null, 0L), "revoked-manager-update");
            case REVOKE -> service.revokeMember(spaceKey, fixture.targetMembership(), "revoked-manager-revoke");
            case CONTENT -> service.createContent(spaceKey,
                    new SpaceDtos.CreateContentRequest("PAGE", "Unauthorized content", "Must be rejected",
                            "INTERNAL", Map.of("body", "Must not be persisted")), "revoked-contributor-content");
            case POLICIES -> service.updatePolicies(spaceKey,
                    new SpaceDtos.UpdatePolicyRequest("OPEN_PUBLISH", "OWNER_MANAGED", "DISABLED", 0L),
                    "revoked-manager-policies");
            case ACCESS_DECISION -> service.decideAccessRequest(spaceKey, fixture.requestId(),
                    new SpaceDtos.AccessDecision("APPROVE", "Must not be approved", 0L), "revoked-manager-access");
        }
    }

    private boolean waitingOnDatabaseLock(int backendPid, Future<?> writer) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock')
                    """, Boolean.class, backendPid);
            if (Boolean.TRUE.equals(waiting)) return true;
            if (writer.isDone()) return false;
            Thread.sleep(20);
        }
        return false;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Owner test synchronization timed out.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Owner test synchronization interrupted.", interrupted);
        }
    }

    private void mutate(Mutation mutation, UUID spaceId, UUID membershipId, String principalRef) {
        switch (mutation) {
            case REVOKE -> commands.revokeMember(subject(TENANT), spaceId, membershipId);
            case UPDATE -> commands.updateMember(subject(TENANT), spaceId, membershipId,
                    new SpaceDtos.UpdateMemberRequest("VIEWER", null, 0L));
            case SAVE -> commands.saveMember(subject(TENANT), spaceId,
                    new SpaceDtos.SaveMemberRequest("USER", principalRef, "VIEWER", null));
        }
    }

    enum Mutation { REVOKE, UPDATE, SAVE }

    enum GuardedWrite { SAVE, UPDATE, REVOKE, CONTENT, POLICIES, ACCESS_DECISION }

    private record AuthorizationFixture(UUID spaceId, UUID actorMembership, UUID targetMembership, UUID requestId) { }
}
