package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalWorkDtos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalOwnedSearchPostgresTest extends ApprovalDraftPostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @BeforeEach void setUp() { initialize(POSTGRES); transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); }
    @AfterEach void tearDown() { clear(); }

    @Test void ownServerPaginationDoesNotTruncateAt200AndHasStableBoundaries() {
        IntStream.range(0, 205).forEach(i -> tx(() -> drafts.create(body("Request " + i), "c" + i, null)));
        context(100, true);
        tx(() -> drafts.create(body("Other owner"), "other", null));
        context(99, true);
        var first = tx(() -> search.requests(ApprovalWorkDtos.RequestView.DRAFTS, filter("", 0, 100)));
        var second = tx(() -> search.requests(ApprovalWorkDtos.RequestView.DRAFTS, filter("", 1, 100)));
        var third = tx(() -> search.requests(ApprovalWorkDtos.RequestView.DRAFTS, filter("", 2, 100)));
        assertThat(first.totalElements()).isEqualTo(205);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.items()).hasSize(100);
        assertThat(second.items()).hasSize(100);
        assertThat(third.items()).hasSize(5);
        assertThat(third.hasNext()).isFalse();
        assertThat(first.items()).doesNotContainAnyElementsOf(second.items());
        assertThat(tx(() -> search.requests(ApprovalWorkDtos.RequestView.DRAFTS, filter("", 100, 100))).totalElements()).isEqualTo(205);
    }

    @Test void searchTreatsWildcardsAndSqlCharactersAsLiteralUserText() {
        tx(() -> drafts.create(body("100%_complete ' OR 1=1 --"), "c1", null));
        tx(() -> drafts.create(body("100AAcomplete"), "c2", null));
        var result = tx(() -> search.requests(ApprovalWorkDtos.RequestView.DRAFTS, filter("%_", 0, 25)));
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.items().getFirst().title()).contains("%_");
        assertThat(tx(() -> search.requests(ApprovalWorkDtos.RequestView.DRAFTS, filter("' OR 1=1 --", 0, 25))).totalElements()).isEqualTo(1);
    }

    @Test void taskSearchFiltersCurrentAuthorityStatusPriorityDueAndWorkflow() {
        var first = tx(() -> drafts.create(body("Pending task"), "c", null));
        tx(() -> { approvals.submit(first.requestId(), first.version(), null); return null; });
        jdbc.update("UPDATE apr_tasks SET due_at=CURRENT_TIMESTAMP-INTERVAL '1 hour' WHERE tenant_id=42");
        var filter = new ApprovalWorkDtos.SearchFilter("Pending", "PENDING", "NORMAL", workflowId,
                ApprovalWorkDtos.DueFilter.OVERDUE, 0, 25, ApprovalWorkDtos.Sort.PRIORITY);
        assertThat(tx(() -> search.tasks(ApprovalWorkDtos.TaskView.INBOX, filter)).totalElements()).isEqualTo(1);
        jdbc.update("UPDATE apr_tasks SET risk_score=69 WHERE tenant_id=42");
        var highRisk = new ApprovalWorkDtos.SearchFilter("", "", "", null, ApprovalWorkDtos.DueFilter.ALL,
                0, 25, ApprovalWorkDtos.Sort.PRIORITY, 70);
        assertThat(tx(() -> search.tasks(ApprovalWorkDtos.TaskView.INBOX, highRisk)).totalElements()).isZero();
        jdbc.update("UPDATE apr_tasks SET risk_score=70 WHERE tenant_id=42");
        assertThat(tx(() -> search.tasks(ApprovalWorkDtos.TaskView.INBOX, highRisk)).totalElements()).isEqualTo(1);
        assertThat(tx(() -> search.tasks(ApprovalWorkDtos.TaskView.COMPLETED, filter("", 0, 25))).items()).isEmpty();
        assertThat(tx(() -> search.tasks(ApprovalWorkDtos.TaskView.INBOX,
                new ApprovalWorkDtos.SearchFilter("", "", "HIGH", null, ApprovalWorkDtos.DueFilter.ALL, 0, 25, ApprovalWorkDtos.Sort.NEWEST))).items()).isEmpty();
        when(identities.require(42, 99)).thenReturn(subject(99, List.of()));
        assertThat(tx(() -> search.tasks(ApprovalWorkDtos.TaskView.INBOX, filter("", 0, 25))).items()).isEmpty();
    }

    @Test void delegatedSearchExcludesCurrentRoleRevocationFromBothItemsAndCount() {
        var first = tx(() -> drafts.create(body("Delegated task"), "c", null));
        tx(() -> { approvals.submit(first.requestId(), first.version(), null); return null; });
        jdbc.update("""
                INSERT INTO apr_delegations(delegation_id,tenant_id,delegator_user_id,delegate_user_id,
                    scope_type,delegated_role_codes,starts_at,ends_at,reason,created_by,updated_by,delegate_display_name)
                VALUES (?,42,100,99,'ALL','["APPROVAL_OPERATOR"]'::jsonb,
                    CURRENT_TIMESTAMP-INTERVAL '1 hour',CURRENT_TIMESTAMP+INTERVAL '1 day','Delegation test',100,100,'Delegate')
                """, UUID.randomUUID());
        assertThat(tx(() -> search.tasks(ApprovalWorkDtos.TaskView.DELEGATED, filter("", 0, 25))).totalElements()).isEqualTo(1);
        when(identities.require(42, 100)).thenReturn(subject(100, List.of()));
        assertThat(tx(() -> search.tasks(ApprovalWorkDtos.TaskView.DELEGATED, filter("", 0, 25))).totalElements()).isZero();
    }

    @Test void claimedRoleDelegationCannotOutliveItsCurrentDelegatedRoleMembership() {
        context(100, true);
        var first = tx(() -> drafts.create(body("Claimed delegated content"), "c", null));
        tx(() -> { approvals.submit(first.requestId(), first.version(), null); return null; });
        context(99, true);
        UUID taskId = jdbc.queryForObject("SELECT task_id FROM apr_tasks WHERE tenant_id=42", UUID.class);
        insertDelegation();
        long taskVersion = jdbc.queryForObject("SELECT version FROM apr_tasks WHERE task_id=?", Long.class, taskId);
        var claimed = tx(() -> approvals.claim(taskId, taskVersion, null));
        assertThat(jdbc.queryForObject("SELECT delegated_authority_role_code FROM apr_tasks WHERE task_id=?",
                String.class, taskId)).isEqualTo("APPROVAL_OPERATOR");
        assertThat(tx(() -> search.tasks(ApprovalWorkDtos.TaskView.INBOX, filter("", 0, 25))).totalElements()).isEqualTo(1);
        jdbc.update("UPDATE apr_delegations SET delegated_role_codes='[]'::jsonb WHERE tenant_id=42");
        for (var view : List.of(ApprovalWorkDtos.TaskView.INBOX, ApprovalWorkDtos.TaskView.DELEGATED)) {
            var page = tx(() -> search.tasks(view, filter("", 0, 25)));
            assertThat(page.totalElements()).isZero();
            assertThat(page.items()).isEmpty();
        }
        when(identities.require(42, 101)).thenReturn(subject(101, List.of("APPROVAL_OPERATOR")));
        jdbc.update("""
                INSERT INTO apr_delegations(delegation_id,tenant_id,delegator_user_id,delegate_user_id,
                    scope_type,delegated_role_codes,starts_at,ends_at,reason,created_by,updated_by,delegate_display_name)
                VALUES (?,42,101,99,'ALL','["APPROVAL_OPERATOR"]'::jsonb,
                    CURRENT_TIMESTAMP-INTERVAL '1 minute',CURRENT_TIMESTAMP+INTERVAL '1 day','Alternate source',101,101,'Delegate')
                """, UUID.randomUUID());
        assertThatThrownBy(() -> tx(() -> approvals.task(taskId)))
                .isInstanceOfSatisfying(BaseException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_AVAILABLE));
        assertThatThrownBy(() -> tx(() -> approvals.decide(taskId,
                new com.dwp.services.approval.domain.ApprovalDtos.DecisionRequest("REJECT", "Valid rejection reason", claimed.task().version()), null)))
                .isInstanceOfSatisfying(BaseException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test void directlyAssignedDelegationDoesNotRequireAnUnrelatedRoleMembership() {
        context(100, true);
        var first = tx(() -> drafts.create(body("Assigned delegation"), "c", null));
        tx(() -> { approvals.submit(first.requestId(), first.version(), null); return null; });
        context(99, true);
        insertDelegation();
        jdbc.update("""
                UPDATE apr_tasks SET status='CLAIMED',assignee_user_id=99,delegated_from_user_id=100,
                    delegated_authority_role_code=NULL WHERE tenant_id=42
                """);
        jdbc.update("UPDATE apr_delegations SET delegated_role_codes='[]'::jsonb WHERE tenant_id=42");
        when(identities.require(42, 100)).thenReturn(subject(100, List.of()));
        var page = tx(() -> search.tasks(ApprovalWorkDtos.TaskView.INBOX, filter("", 0, 25)));
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        var task = page.items().getFirst();
        var decided = tx(() -> approvals.decide(task.taskId(),
                new com.dwp.services.approval.domain.ApprovalDtos.DecisionRequest("REJECT", "Valid rejection reason", task.version()), null));
        assertThat(decided.task().status()).isEqualTo("REJECTED");
    }

    @Test void revocationCommittedAfterTheReadPrecheckFencesTheActualDecisionWrite() throws Exception {
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        context(100, true);
        var first = tx(() -> drafts.create(body("Concurrent revocation"), "c", null));
        tx(() -> { approvals.submit(first.requestId(), first.version(), null); return null; });
        context(99, true);
        insertDelegation();
        UUID taskId = jdbc.queryForObject("SELECT task_id FROM apr_tasks WHERE tenant_id=42", UUID.class);
        long version = jdbc.queryForObject("SELECT version FROM apr_tasks WHERE task_id=?", Long.class, taskId);
        tx(() -> approvals.claim(taskId, version, null));
        long audits = count("sys_audit_outbox"), events = count("apr_request_events"), outbox = count("apr_integration_outbox");
        var checked = new CountDownLatch(1);
        var revoked = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var result = pool.submit(() -> {
                context(99, false);
                try {
                    tx(() -> {
                        var stale = queries.taskDetail(ApprovalRequestContext.require(), taskId);
                        approvals.task(taskId);
                        checked.countDown();
                        try { if (!revoked.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Revocation timed out"); }
                        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                        return commands.decide(ApprovalRequestContext.require(), stale,
                                new com.dwp.services.approval.domain.ApprovalDtos.DecisionRequest("REJECT", "Valid rejection reason", stale.summary().version()), null);
                    });
                    return null;
                } catch (BaseException exception) { return exception.getErrorCode(); }
                finally { clear(); }
            });
            assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE apr_delegations SET delegated_role_codes='[]'::jsonb WHERE tenant_id=42");
            revoked.countDown();
            assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
            assertThat(jdbc.queryForObject("SELECT status FROM apr_tasks WHERE task_id=?", String.class, taskId)).isEqualTo("CLAIMED");
            assertThat(count("sys_audit_outbox")).isEqualTo(audits);
            assertThat(count("apr_request_events")).isEqualTo(events);
            assertThat(count("apr_integration_outbox")).isEqualTo(outbox);
        } finally { revoked.countDown(); pool.shutdownNow(); }
    }

    private void insertDelegation() {
        jdbc.update("""
                INSERT INTO apr_delegations(delegation_id,tenant_id,delegator_user_id,delegate_user_id,
                    scope_type,delegated_role_codes,starts_at,ends_at,reason,created_by,updated_by,delegate_display_name)
                VALUES (?,42,100,99,'ALL','["APPROVAL_OPERATOR"]'::jsonb,
                    CURRENT_TIMESTAMP-INTERVAL '1 hour',CURRENT_TIMESTAMP+INTERVAL '1 day','Delegation test',100,100,'Delegate')
                """, UUID.randomUUID());
    }

    @Test void inactiveTenantAndDeletedDraftsCannotEnterOrdinaryResults() {
        var first = tx(() -> drafts.create(body("Deleted"), "c", null));
        tx(() -> drafts.delete(first.requestId(), new ApprovalWorkDtos.DraftCommand(first.version(), "d", "Delete"), null));
        assertThat(tx(() -> search.requests(ApprovalWorkDtos.RequestView.DRAFTS, filter("", 0, 25))).totalElements()).isZero();
        assertThat(tx(() -> search.requests(ApprovalWorkDtos.RequestView.DELETED, filter("", 0, 25))).totalElements()).isEqualTo(1);
        jdbc.update("UPDATE apr_tenants SET lifecycle_state='SUSPENDED' WHERE tenant_id=42");
        assertThatThrownBy(() -> tx(() -> search.requests(ApprovalWorkDtos.RequestView.DELETED, filter("", 0, 25))))
                .isInstanceOfSatisfying(BaseException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test void malformedFiltersAndCurrentAuthFailureNeverFallBackToBroadList() {
        tx(() -> drafts.create(body("Secret"), "c", null));
        assertThatThrownBy(() -> tx(() -> search.requests(ApprovalWorkDtos.RequestView.DRAFTS, filter("", -1, 25))))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> tx(() -> search.tasks(ApprovalWorkDtos.TaskView.INBOX,
                new ApprovalWorkDtos.SearchFilter("", "anything", "", null, ApprovalWorkDtos.DueFilter.ALL, 0, 25, ApprovalWorkDtos.Sort.NEWEST))))
                .isInstanceOf(BaseException.class);
        when(identities.require(42, 99)).thenThrow(new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "unavailable"));
        assertThatThrownBy(() -> tx(() -> search.requests(ApprovalWorkDtos.RequestView.DRAFTS, filter("", 0, 25))))
                .isInstanceOfSatisfying(BaseException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    @Test void todayUsesTenantTimeZoneNotThePostgresSessionDate() {
        var first = tx(() -> drafts.create(body("Tenant today"), "c", null));
        tx(() -> { approvals.submit(first.requestId(), first.version(), null); return null; });
        jdbc.update("UPDATE apr_tenants SET default_time_zone='Asia/Seoul' WHERE tenant_id=42");
        jdbc.update("UPDATE apr_tasks SET due_at=(date_trunc('day',CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')"
                + "+INTERVAL '1 hour') AT TIME ZONE 'Asia/Seoul' WHERE tenant_id=42");
        var today = new ApprovalWorkDtos.SearchFilter("", "", "", null, ApprovalWorkDtos.DueFilter.TODAY,
                0, 25, ApprovalWorkDtos.Sort.NEWEST);
        tx(() -> {
            jdbc.execute("SET LOCAL TIME ZONE 'Pacific/Honolulu'");
            assertThat(search.tasks(ApprovalWorkDtos.TaskView.INBOX, today).totalElements()).isEqualTo(1);
            return null;
        });
        jdbc.update("UPDATE apr_tasks SET due_at=(date_trunc('day',CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')"
                + "+INTERVAL '1 day') AT TIME ZONE 'Asia/Seoul' WHERE tenant_id=42");
        assertThat(tx(() -> search.tasks(ApprovalWorkDtos.TaskView.INBOX, today)).totalElements()).isZero();
    }
}
