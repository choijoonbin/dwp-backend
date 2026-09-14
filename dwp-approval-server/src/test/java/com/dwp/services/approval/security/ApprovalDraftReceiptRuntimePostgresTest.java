package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.api.ApprovalController;
import com.dwp.services.approval.api.ApprovalDraftController;
import com.dwp.services.approval.api.ApprovalSearchController;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalWorkDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalDraftReceiptRuntimePostgresTest extends ApprovalDraftPostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private ApprovalSecurityFilter filter;
    private ApprovalController controller;
    private ApprovalDraftController draftController;
    private ApprovalSearchController searchController;

    @BeforeEach void setUp() {
        initialize(POSTGRES);
        filter = new ApprovalSecurityFilter("trusted", "runtime", true, new ObjectMapper().findAndRegisterModules());
        controller = new ApprovalController(approvals, drafts);
        draftController = new ApprovalDraftController(drafts);
        searchController = new ApprovalSearchController(search);
    }
    @AfterEach void tearDown() { clear(); }

    @ParameterizedTest @ValueSource(strings = {"000", "100", "110", "111"})
    void realFilterControllerAndPostgresReceiptReplayAcrossAllRollouts(String state) throws Exception {
        var create = request("POST", "/v1/requests", "route.approvals.work.request-create.action", state);
        var first = invoke(create, () -> controller.create(body("Runtime"), "create-key", "corr").getData());
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.value()).isNotNull();
        var repeat = invoke(request("POST", "/v1/requests", "route.approvals.work.request-create.action", state),
                () -> controller.create(body("Runtime"), "create-key", "unknown-response-replay").getData());
        assertThat(repeat.value()).isEqualTo(first.value());
        assertThat(count("apr_requests")).isEqualTo(1);
        var update = update(first.value(), "Autosaved", "Updated");
        String path = "/v1/requests/" + first.value().requestId() + "/draft";
        var saved = invoke(request("PUT", path, "route.approvals.work.request-draft-update.action", state),
                () -> controller.updateDraft(first.value().requestId(), update, "update-key", "corr").getData());
        assertThat(saved.status()).isEqualTo(200);
        var replay = invoke(request("PUT", path, "route.approvals.work.request-draft-update.action", state),
                () -> controller.updateDraft(first.value().requestId(), update, "update-key", "lost-response").getData());
        assertThat(replay.value()).isEqualTo(saved.value());
        assertThat(count("apr_request_payload_versions")).isEqualTo(2);
        assertThat(count("apr_draft_commands")).isEqualTo(2);
    }

    @Test void wrongExactRouteAndRuntimeIdentityCannotOpenNewDraftCommand() throws Exception {
        var first = tx(() -> drafts.create(body("Owned"), "c", null));
        String path = "/v1/requests/" + first.requestId() + "/draft/delete";
        var request = request("POST", path, "route.approvals.work.request-submit.action", "110");
        var wrongRoute = invoke(request, () -> draftController.delete(first.requestId(),
                new ApprovalWorkDtos.DraftCommand(first.version(), "d", "reason"), null).getData());
        assertThat(wrongRoute.status()).isEqualTo(403);
        assertThat(wrongRoute.value()).isNull();
        request = request("POST", path, "route.approvals.work.request-submit.action", "110");
        request.removeHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER);
        request.addHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER, "runtime");
        assertThat(invoke(request, () -> draftController.delete(first.requestId(),
                new ApprovalWorkDtos.DraftCommand(first.version(), "d", "reason"), null).getData()).status()).isEqualTo(401);
        assertThat(count("apr_draft_commands")).isEqualTo(1);
    }

    @Test void exactDecisionRevisionConflictAndCurrentAuthFailureReturn409And503WithoutReceipt() throws Exception {
        var conflict = request("POST", "/v1/requests", "route.approvals.work.request-create.action", "111");
        conflict.removeHeader(ApprovalSecurityFilter.EXPECTED_DECISION_REVISION_HEADER);
        conflict.addHeader(ApprovalSecurityFilter.EXPECTED_DECISION_REVISION_HEADER, "different");
        assertThat(invoke(conflict, () -> controller.create(body("Never"), "c", null).getData()).status()).isEqualTo(409);
        when(identities.require(42, 99)).thenThrow(new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Auth down"));
        var down = request("POST", "/v1/requests", "route.approvals.work.request-create.action", "110");
        assertThat(invoke(down, () -> controller.create(body("Never"), "c", null).getData()).status()).isEqualTo(503);
        assertThat(count("apr_draft_commands")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"000", "100", "110", "111"})
    void allNewExactEndpointsReachTheirRealOwnerServiceAndPostgres(String state) throws Exception {
        var first = invoke(request("POST", "/v1/requests", "route.approvals.work.request-create.action", state),
                () -> controller.create(body("New endpoints"), "c", null).getData()).value();
        String base = "/v1/requests/" + first.requestId() + "/draft/";
        var revisions = invoke(request("GET", base + "revisions", "route.approvals.work.request-draft-revisions.data", state),
                () -> draftController.revisions(first.requestId(), 0, 25).getData());
        assertThat(revisions.status()).isEqualTo(200);
        assertThat(revisions.value().totalElements()).isEqualTo(1);
        var revision = invoke(request("GET", base + "revisions/1", "route.approvals.work.request-draft-revision.data", state),
                () -> draftController.revision(first.requestId(), 1).getData());
        assertThat(revision.status()).isEqualTo(200);
        assertThat(revision.value().payload()).containsKey("systemName");
        var requestPage = invoke(request("GET", "/v1/requests/search", "route.approvals.work.requests-search.data", state),
                () -> searchController.requests(ApprovalWorkDtos.RequestView.DRAFTS, "New", "", "", null,
                        ApprovalWorkDtos.DueFilter.ALL, 0, 25, ApprovalWorkDtos.Sort.NEWEST).getData());
        assertThat(requestPage.status()).isEqualTo(200);
        assertThat(requestPage.value().totalElements()).isEqualTo(1);
        var taskPage = invoke(request("GET", "/v1/tasks/search", "route.approvals.work.tasks-search.data", state),
                () -> searchController.tasks(ApprovalWorkDtos.TaskView.INBOX, "", "", "", null,
                        ApprovalWorkDtos.DueFilter.ALL, 0, 25, ApprovalWorkDtos.Sort.PRIORITY, 70).getData());
        assertThat(taskPage.status()).isEqualTo(200);
        assertThat(taskPage.value().items()).isEmpty();
        var recovered = invoke(request("POST", base + "recover", "route.approvals.work.request-draft-recover.action", state),
                () -> draftController.recover(first.requestId(), new ApprovalWorkDtos.RecoverDraft(1, first.version(), "recover", "Original"), null).getData());
        assertThat(recovered.status()).isEqualTo(200);
        var deleted = invoke(request("POST", base + "delete", "route.approvals.work.request-draft-delete.action", state),
                () -> draftController.delete(first.requestId(), new ApprovalWorkDtos.DraftCommand(recovered.value().version(), "delete", "Trash"), null).getData());
        assertThat(deleted.status()).isEqualTo(200);
        assertThat(deleted.value().deletedAt()).isNotNull();
        var restored = invoke(request("POST", base + "restore", "route.approvals.work.request-draft-restore.action", state),
                () -> draftController.restore(first.requestId(), new ApprovalWorkDtos.DraftCommand(deleted.value().version(), "restore", "Undo"), null).getData());
        assertThat(restored.status()).isEqualTo(200);
        assertThat(restored.value().deletedAt()).isNull();
        var receipt = invoke(request("GET", "/v1/draft-commands/c", "route.approvals.work.draft-command-reconciliation.data", state),
                () -> draftController.reconcile("c").getData());
        assertThat(receipt.status()).isEqualTo(200);
        assertThat(receipt.value().receipts().getFirst().draft().requestId()).isEqualTo(first.requestId());
    }

    @Test void newExactEndpointCannotBorrowSupportModeOrReadOnlyAuthority() throws Exception {
        var first = tx(() -> drafts.create(body("Owner"), "c", null));
        var support = request("POST", "/v1/requests/" + first.requestId() + "/draft/delete",
                "route.approvals.work.request-draft-delete.action", "111");
        support.removeHeader(ApprovalSecurityFilter.ACTIVE_ACCESS_MODE_HEADER);
        support.addHeader(ApprovalSecurityFilter.ACTIVE_ACCESS_MODE_HEADER, "PROVIDER_SUPPORT");
        assertThat(invoke(support, () -> draftController.delete(first.requestId(),
                new ApprovalWorkDtos.DraftCommand(first.version(), "d", "Trash"), null).getData()).status()).isEqualTo(403);
        var readOnly = request("POST", "/v1/requests/" + first.requestId() + "/draft/delete",
                "route.approvals.work.request-draft-delete.action", "110");
        readOnly.removeHeader(ApprovalSecurityFilter.PERMISSIONS_HEADER);
        readOnly.addHeader(ApprovalSecurityFilter.PERMISSIONS_HEADER, "APP.APPROVALS:VIEW,ACTION.APPROVAL_REQUEST:VIEW");
        assertThat(invoke(readOnly, () -> draftController.delete(first.requestId(),
                new ApprovalWorkDtos.DraftCommand(first.version(), "d", "Trash"), null).getData()).status()).isEqualTo(403);
        assertThat(count("apr_draft_commands")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"000", "100", "110", "111"})
    void wrongActorTenantModePermissionAndVersionRemainClosedAcrossAllRollouts(String state) throws Exception {
        var first = tx(() -> drafts.create(body("Private"), "c", null));
        String path = "/v1/requests/" + first.requestId() + "/draft/delete";
        String key = "route.approvals.work.request-draft-delete.action";
        var body = new ApprovalWorkDtos.DraftCommand(first.version(), "d", "Delete");
        var otherActor = request("POST", path, key, state);
        otherActor.removeHeader(ApprovalSecurityFilter.USER_HEADER); otherActor.addHeader(ApprovalSecurityFilter.USER_HEADER, "100");
        assertThat(invoke(otherActor, () -> draftController.delete(first.requestId(), body, null).getData()).status()).isEqualTo(403);
        when(identities.require(84, 99)).thenReturn(new com.dwp.services.approval.integration.ApprovalIdentityDirectory.Subject(
                84L, 99L, null, null, "Owner", "x@example.test", null, "ACTIVE", java.util.List.of(), java.util.List.copyOf(PERMISSIONS)));
        var otherTenant = request("POST", path, key, state);
        otherTenant.removeHeader(ApprovalSecurityFilter.TENANT_HEADER); otherTenant.addHeader(ApprovalSecurityFilter.TENANT_HEADER, "84");
        assertThat(invoke(otherTenant, () -> draftController.delete(first.requestId(), body, null).getData()).status()).isEqualTo(403);
        var support = request("POST", path, key, state);
        support.removeHeader(ApprovalSecurityFilter.ACTIVE_ACCESS_MODE_HEADER); support.addHeader(ApprovalSecurityFilter.ACTIVE_ACCESS_MODE_HEADER, "PROVIDER_SUPPORT");
        assertThat(invoke(support, () -> draftController.delete(first.requestId(), body, null).getData()).status()).isEqualTo(403);
        var readOnly = request("POST", path, key, state);
        readOnly.removeHeader(ApprovalSecurityFilter.PERMISSIONS_HEADER); readOnly.addHeader(ApprovalSecurityFilter.PERMISSIONS_HEADER, "APP.APPROVALS:VIEW,ACTION.APPROVAL_REQUEST:VIEW");
        assertThat(invoke(readOnly, () -> draftController.delete(first.requestId(), body, null).getData()).status()).isEqualTo(403);
        assertThat(invoke(request("POST", path, key, state), () -> draftController.delete(first.requestId(),
                new ApprovalWorkDtos.DraftCommand(999L, "v", "Delete"), null).getData()).status()).isEqualTo(409);
        when(identities.require(42, 99)).thenThrow(new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Auth down"));
        assertThat(invoke(request("POST", path, key, state), () -> draftController.delete(first.requestId(), body, null).getData()).status()).isEqualTo(503);
        assertThat(count("apr_draft_commands")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"000", "100", "110", "111"})
    void springReadAliasesCannotHideSupportContextFromTheWorkBoundary(String state) throws Exception {
        for (String path : java.util.List.of("/v1/requests/search;x=1", "/v1/requests/%73earch")) {
            var alias = request("GET", path, "route.approvals.work.requests-search.data", state);
            alias.addHeader("X-DWP-Support-Session-ID", "support");
            assertThat(invoke(alias, () -> searchController.requests(ApprovalWorkDtos.RequestView.DRAFTS, "", "", "", null,
                    ApprovalWorkDtos.DueFilter.ALL, 0, 25, ApprovalWorkDtos.Sort.NEWEST).getData()).status()).isEqualTo(403);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"000", "100", "110", "111"})
    void malformedTrustedPlaneAndModeNeverReachTheOwnerService(String state) throws Exception {
        for (String header : java.util.List.of("X-DWP-Identity-Plane", ApprovalSecurityFilter.ACTIVE_ACCESS_MODE_HEADER)) {
            var malformed = request("GET", "/v1/draft-commands/c",
                    "route.approvals.work.draft-command-reconciliation.data", state);
            malformed.removeHeader(header);
            malformed.addHeader(header, " ");
            assertThat(invoke(malformed, () -> draftController.reconcile("c").getData()).status()).isEqualTo(503);
            var duplicate = request("GET", "/v1/draft-commands/c",
                    "route.approvals.work.draft-command-reconciliation.data", state);
            duplicate.addHeader(header, "TENANT");
            duplicate.addHeader(header, "PROVIDER_SUPPORT");
            assertThat(invoke(duplicate, () -> draftController.reconcile("c").getData()).status()).isEqualTo(503);
        }
        assertThat(count("apr_draft_commands")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"110", "111"})
    void searchReadsCannotBorrowGenericObjectReadBindings(String state) throws Exception {
        var requestSearch = request("GET", "/v1/requests/search",
                "route.approvals.work.request-detail.data", state);
        assertThat(invoke(requestSearch, () -> searchController.requests(ApprovalWorkDtos.RequestView.DRAFTS,
                "", "", "", null, ApprovalWorkDtos.DueFilter.ALL, 0, 25, ApprovalWorkDtos.Sort.NEWEST).getData()).status())
                .isEqualTo(403);
        var taskSearch = request("GET", "/v1/tasks/search", "route.approvals.work.task-detail.data", state);
        assertThat(invoke(taskSearch, () -> searchController.tasks(ApprovalWorkDtos.TaskView.INBOX,
                "", "", "", null, ApprovalWorkDtos.DueFilter.ALL, 0, 25, ApprovalWorkDtos.Sort.PRIORITY, 70).getData()).status())
                .isEqualTo(403);
        assertThat(count("apr_draft_commands")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"000", "100", "110", "111"})
    void claimedRoleRemovalClosesSearchCountAndContentAcrossAllRollouts(String state) throws Exception {
        context(100, true);
        var first = tx(() -> drafts.create(body("Delegated confidential title"), "c", null));
        tx(() -> { approvals.submit(first.requestId(), first.version(), null); return null; });
        context(99, true);
        var taskId = jdbc.queryForObject("SELECT task_id FROM apr_tasks WHERE tenant_id=42", java.util.UUID.class);
        jdbc.update("""
                INSERT INTO apr_delegations(delegation_id,tenant_id,delegator_user_id,delegate_user_id,
                    scope_type,delegated_role_codes,starts_at,ends_at,reason,created_by,updated_by,delegate_display_name)
                VALUES (?,42,100,99,'ALL','["APPROVAL_OPERATOR"]'::jsonb,
                    CURRENT_TIMESTAMP-INTERVAL '1 hour',CURRENT_TIMESTAMP+INTERVAL '1 day','Delegation test',100,100,'Delegate')
                """, java.util.UUID.randomUUID());
        long taskVersion = jdbc.queryForObject("SELECT version FROM apr_tasks WHERE task_id=?", Long.class, taskId);
        var claimed = tx(() -> approvals.claim(taskId, taskVersion, null));
        assertThat(jdbc.queryForObject("SELECT delegated_authority_role_code FROM apr_tasks WHERE task_id=?",
                String.class, taskId)).isEqualTo("APPROVAL_OPERATOR");
        var before = invoke(request("GET", "/v1/tasks/search", "route.approvals.work.tasks-search.data", state),
                () -> searchController.tasks(ApprovalWorkDtos.TaskView.INBOX, "", "", "", null,
                        ApprovalWorkDtos.DueFilter.ALL, 0, 25, ApprovalWorkDtos.Sort.NEWEST, null).getData());
        assertThat(before.status()).isEqualTo(200);
        assertThat(before.value().totalElements()).isEqualTo(1);
        long audits = count("sys_audit_outbox");
        long events = count("apr_request_events");
        long outbox = count("apr_integration_outbox");
        long requestVersion = jdbc.queryForObject("SELECT version FROM apr_requests WHERE request_id=?", Long.class, first.requestId());
        jdbc.update("UPDATE apr_delegations SET delegated_role_codes='[]'::jsonb WHERE tenant_id=42");
        for (var view : java.util.List.of(ApprovalWorkDtos.TaskView.INBOX, ApprovalWorkDtos.TaskView.DELEGATED)) {
            var after = invoke(request("GET", "/v1/tasks/search", "route.approvals.work.tasks-search.data", state),
                    () -> searchController.tasks(view, "", "", "", null,
                            ApprovalWorkDtos.DueFilter.ALL, 0, 25, ApprovalWorkDtos.Sort.NEWEST, null).getData());
            assertThat(after.status()).isEqualTo(200);
            assertThat(after.value().totalElements()).isZero();
            assertThat(after.value().items()).isEmpty();
        }
        when(identities.require(42, 101)).thenReturn(subject(101, java.util.List.of("APPROVAL_OPERATOR")));
        jdbc.update("""
                INSERT INTO apr_delegations(delegation_id,tenant_id,delegator_user_id,delegate_user_id,
                    scope_type,delegated_role_codes,starts_at,ends_at,reason,created_by,updated_by,delegate_display_name)
                VALUES (?,42,101,99,'ALL','["APPROVAL_OPERATOR"]'::jsonb,
                    CURRENT_TIMESTAMP-INTERVAL '1 minute',CURRENT_TIMESTAMP+INTERVAL '1 day','Alternate source',101,101,'Delegate')
                """, java.util.UUID.randomUUID());
        var deniedRead = invoke(request("GET", "/v1/tasks/" + taskId, "route.approvals.work.task-detail.data", state),
                () -> controller.task(taskId).getData());
        assertThat(deniedRead.status()).isEqualTo(404);
        assertThat(deniedRead.value()).isNull();
        var decide = request("POST", "/v1/tasks/" + taskId + "/decisions", "route.approvals.work.task-decision.action", state);
        decide.removeHeader(ApprovalSecurityFilter.PERMISSIONS_HEADER);
        decide.addHeader(ApprovalSecurityFilter.PERMISSIONS_HEADER, String.join(",", PERMISSIONS) + ",ACTION.APPROVAL_TASK:APPROVE");
        var deniedDecision = invoke(decide, () -> controller.decide(taskId,
                new ApprovalDtos.DecisionRequest("REJECT", "Valid rejection reason", claimed.task().version()), null).getData());
        assertThat(deniedDecision.status()).isEqualTo(state.charAt(1) == '1' ? 404 : 409);
        assertThat(deniedDecision.value()).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM apr_tasks WHERE task_id=?", String.class, taskId)).isEqualTo("CLAIMED");
        assertThat(jdbc.queryForObject("SELECT version FROM apr_tasks WHERE task_id=?", Long.class, taskId)).isEqualTo(claimed.task().version());
        assertThat(jdbc.queryForObject("SELECT version FROM apr_requests WHERE request_id=?", Long.class, first.requestId())).isEqualTo(requestVersion);
        assertThat(count("sys_audit_outbox")).isEqualTo(audits);
        assertThat(count("apr_request_events")).isEqualTo(events);
        assertThat(count("apr_integration_outbox")).isEqualTo(outbox);
        var commentDecision = request("POST", "/v1/tasks/" + taskId + "/decisions", "route.approvals.work.task-decision.action", state);
        commentDecision.removeHeader(ApprovalSecurityFilter.PERMISSIONS_HEADER);
        commentDecision.addHeader(ApprovalSecurityFilter.PERMISSIONS_HEADER, String.join(",", PERMISSIONS) + ",ACTION.APPROVAL_TASK:APPROVE");
        var deniedInformation = invoke(commentDecision, () -> controller.decide(taskId,
                new ApprovalDtos.DecisionRequest("REQUEST_INFO", "Information response request comment", claimed.task().version()), null).getData());
        assertThat(deniedInformation.status()).isEqualTo(state.charAt(1) == '1' ? 404 : 409);
        assertThat(deniedInformation.value()).isNull();
        assertThat(count("sys_audit_outbox")).isEqualTo(audits);
        assertThat(count("apr_request_events")).isEqualTo(events);
        assertThat(count("apr_integration_outbox")).isEqualTo(outbox);

        context(100, true);
        var other = tx(() -> drafts.create(body("New claim uses its own eligible source"), "other", null));
        tx(() -> { approvals.submit(other.requestId(), other.version(), null); return null; });
        context(99, true);
        var otherTaskId = jdbc.queryForObject("SELECT task_id FROM apr_tasks WHERE request_id=?", java.util.UUID.class, other.requestId());
        long otherVersion = jdbc.queryForObject("SELECT version FROM apr_tasks WHERE task_id=?", Long.class, otherTaskId);
        var otherClaim = tx(() -> approvals.claim(otherTaskId, otherVersion, null));
        assertThat(jdbc.queryForObject("SELECT delegated_from_user_id FROM apr_tasks WHERE task_id=?", Long.class, otherTaskId)).isEqualTo(101);
        var otherRead = invoke(request("GET", "/v1/tasks/" + otherTaskId, "route.approvals.work.task-detail.data", state),
                () -> controller.task(otherTaskId).getData());
        assertThat(otherRead.status()).isEqualTo(200);
        assertThat(otherRead.value().contentAccess().state()).isEqualTo("FULL");
        var otherDecide = request("POST", "/v1/tasks/" + otherTaskId + "/decisions", "route.approvals.work.task-decision.action", state);
        otherDecide.removeHeader(ApprovalSecurityFilter.PERMISSIONS_HEADER);
        otherDecide.addHeader(ApprovalSecurityFilter.PERMISSIONS_HEADER, String.join(",", PERMISSIONS) + ",ACTION.APPROVAL_TASK:APPROVE");
        var otherDecision = invoke(otherDecide, () -> controller.decide(otherTaskId,
                new ApprovalDtos.DecisionRequest("REJECT", "Valid rejection reason", otherClaim.task().version()), null).getData());
        assertThat(otherDecision.status()).isEqualTo(200);
        assertThat(otherDecision.value().task().status()).isEqualTo("REJECTED");
    }

    private <T> Invocation<T> invoke(MockHttpServletRequest request, Supplier<T> action) throws Exception {
        var response = new MockHttpServletResponse();
        var value = new AtomicReference<T>();
        String state = request.getHeader(ApprovalSecurityFilter.ROLLOUT_STATE_HEADER);
        var runtimeFilter = "000".equals(state) || "100".equals(state)
                ? new ApprovalSecurityFilter("trusted", "runtime", false, new ObjectMapper().findAndRegisterModules()) : filter;
        try { runtimeFilter.doFilter(request, response, (req, res) -> value.set(tx(action))); }
        catch (BaseException exception) { response.setStatus(exception.getErrorCode().getHttpStatus().value()); }
        return new Invocation<>(response.getStatus(), value.get());
    }

    private MockHttpServletRequest request(String method, String path, String key, String state) {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(ApprovalSecurityFilter.USER_HEADER, "99");
        request.addHeader(ApprovalSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(ApprovalSecurityFilter.ROLES_HEADER, "APPROVAL_OPERATOR");
        request.addHeader(ApprovalSecurityFilter.PERMISSIONS_HEADER, String.join(",", PERMISSIONS));
        request.addHeader("X-DWP-Identity-Plane", "TENANT");
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_STATE_HEADER, state);
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_COHORT_HEADER, "full");
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_REVISION_HEADER, "rollout-" + "a".repeat(64));
        if (state.charAt(1) == '1') {
            request.addHeader(ApprovalSecurityFilter.ROUTE_CONTRACT_HEADER, key);
            request.addHeader(ApprovalSecurityFilter.CURRENT_CONTEXT_HEADER, "approvals.work");
            request.addHeader(ApprovalSecurityFilter.CURRENT_SCOPE_HEADER, "own");
            request.addHeader(ApprovalSecurityFilter.ACTIVE_ACCESS_MODE_HEADER, "NORMAL");
            request.addHeader(ApprovalSecurityFilter.CURRENT_DECISION_REVISION_HEADER, "psr-" + "b".repeat(64));
            request.addHeader(ApprovalSecurityFilter.EXPECTED_DECISION_REVISION_HEADER, "psr-" + "b".repeat(64));
            request.addHeader(ApprovalSecurityFilter.CURRENT_DECISION_REVALIDATE_AT_HEADER, OffsetDateTime.now().plusMinutes(5).toString());
        }
        return request;
    }

    private record Invocation<T>(int status, T value) { }
}
