package com.dwp.services.approval.security;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ScopedAuthorityToken;
import com.dwp.services.approval.domain.ApprovalCommandRepository;
import com.dwp.services.approval.domain.ApprovalDelegationCommandSupport;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalQueryRepository;
import com.dwp.services.approval.domain.ApprovalService;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.support.PilotAuthorizationFixtureAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.dwp.services.approval.security.ApprovalPilotAcceptanceMatrixTest.Mutation.*;

/** Generated fixture -> exact PEP -> owner-service mutation acceptance matrix for W1a. */
class ApprovalPilotAcceptanceMatrixTest {

    private static final UUID WORKFLOW_ID = uuid(1);
    private static final UUID FORM_ID = uuid(2);
    private static final UUID POLICY_ID = uuid(3);
    private static final UUID OUTBOX_ID = uuid(4);
    private static final UUID TASK_ID = uuid(5);
    private static final UUID REQUEST_ID = uuid(6);
    private static final String RESOURCE_ROLE = "APP_CONFIG_ADMIN@RS_APPROVALS";

    @AfterEach
    void clearContexts() {
        ApprovalPilotAuthorizationContext.clear();
        ApprovalDecisionRevisionContext.clear();
        ApprovalManagementScopeContext.clear();
        ApprovalRequestContext.clear();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void executesAllApprovalPilotCasesAgainstPepPredicatesAndMutationBoundary(
            Scenario scenario) {
        PilotAuthorizationFixtureAdapter.ApprovalPepFixture fixture =
                new PilotAuthorizationFixtureAdapter().project(scenario.testId());
        assertThat(fixture.expectedOutcome()).isEqualTo(scenario.expectedOutcome());
        assertThat(fixture.testId()).isEqualTo(scenario.testId());

        Harness harness = new Harness();
        ApprovalPilotPepRegistry.Decision decision = harness.resolve(
                scenario.method(), scenario.path(), scenario.permissions(),
                scenario.resourceRoles(), scenario.roles());
        assertThat(decision.allowed()).isEqualTo(scenario.pepAllowed());
        assertScenarioBoundaries(scenario, harness);

        if (decision.allowed()) {
            ApprovalPilotAuthorizationContext.set(decision.authorities());
            String routeContractKey = harness.canonicalRoute(scenario.method(), scenario.path());
            ApprovalDecisionRevisionContext.set(
                    "fixture-decision-revision", OffsetDateTime.now().plusHours(1),
                    "approval-management", "fixture-scope-opaque", routeContractKey, "111");
            ApprovalManagementScopeContext.set(
                    "fixture-scope-opaque", "RS_APPROVALS");
        }
        ApprovalRequestContext.set(
                17L, 42L, null, scenario.roles(), scenario.permissions());
        harness.execute(scenario.mutation());

        assertThat(mockingDetails(harness.commands).getInvocations())
                .as("%s domain mutation count", scenario.testId())
                .hasSize(scenario.expectedMutationCount());
    }

    private static Stream<Scenario> scenarios() {
        Set<String> work = Set.of(
                "APP.APPROVALS:VIEW",
                "ACTION.APPROVAL_TASK:VIEW",
                "ACTION.APPROVAL_TASK:UPDATE",
                "ACTION.APPROVAL_TASK:APPROVE",
                "ACTION.APPROVAL_REQUEST:VIEW",
                "ACTION.APPROVAL_REQUEST:CREATE",
                "ACTION.APPROVAL_REQUEST:UPDATE",
                "ACTION.APPROVAL_DELEGATION:VIEW",
                "ACTION.APPROVAL_DELEGATION:MANAGE");
        return Stream.of(
                scenario("PS-A001", "WORK_ONLY", "GET", "/v1/home",
                        Set.of("APP.APPROVALS:VIEW"), "", Set.of(), true, NONE, 0),
                scenario("PS-A002", "DESIGN_DRAFT_ONLY", "POST", "/v1/admin/workflows",
                        Set.of("ADMIN.APPROVAL_DESIGN:CREATE"), scoped(
                                "approvals.design.create", "ADMIN.APPROVAL_DESIGN:CREATE"), Set.of(),
                        true, DESIGN_DRAFT, 2),
                scenario("PS-A003", "BOUND_WORKFLOW_PUBLISH_ONCE", "POST",
                        "/v1/admin/workflows/" + WORKFLOW_ID + "/publish",
                        Set.of("ADMIN.APPROVAL_DESIGN:PUBLISH"), scoped(
                                "approvals.design.publish", "ADMIN.APPROVAL_DESIGN:PUBLISH"), Set.of(),
                        true, WORKFLOW_PUBLISH, 1),
                scenario("PS-A004", "BOUND_RECOVERY_ONCE", "POST",
                        "/v1/admin/operations/events/" + OUTBOX_ID + "/retry",
                        Set.of("ADMIN.APPROVAL_OPERATIONS:EXECUTE"), scoped(
                                "approvals.operations.execute",
                                "ADMIN.APPROVAL_OPERATIONS:EXECUTE"), Set.of(),
                        true, RECOVERY, 1),
                scenario("PS-A005", "EVIDENCE_READ_ONLY", "GET", "/v1/admin/operations",
                        Set.of("ADMIN.APPROVAL_OPERATIONS:VIEW"), scopedAudit(
                                "approvals.audit.operations.read",
                                "ADMIN.APPROVAL_OPERATIONS:VIEW"), Set.of(),
                        true, NONE, 0),
                scenario("PS-A006", "MANAGEMENT_ROOT_WORK_DENIED", "POST",
                        "/v1/admin/workflows", Set.of("ADMIN.APPROVAL_DESIGN:CREATE"),
                        scoped("approvals.design.create", "ADMIN.APPROVAL_DESIGN:CREATE"),
                        Set.of(), true, NONE, 0),
                scenario("PS-A007", "SOD_ASSIGNMENT_DENIED", "POST",
                        "/v1/admin/workflows/" + WORKFLOW_ID + "/publish",
                        Set.of("ADMIN.APPROVAL_DESIGN:CREATE", "ADMIN.APPROVAL_DESIGN:PUBLISH"),
                        scoped("approvals.design.publish", "ADMIN.APPROVAL_DESIGN:PUBLISH")
                                + ',' + ScopedAuthorityToken.wireToken(
                                        "approvals.design.create",
                                        "ADMIN.APPROVAL_DESIGN:CREATE", "RS_APPROVALS"),
                        Set.of(), false, NONE, 0),
                scenario("PS-A008", "SINGLE_EXPIRY_WARNING", "GET", "/v1/admin/overview",
                        Set.of("ADMIN.APPROVAL_OPERATIONS:VIEW"), scoped(
                                "approvals.operations.read", "ADMIN.APPROVAL_OPERATIONS:VIEW"), Set.of(),
                        true, NONE, 0),
                scenario("PS-A009", "IMMEDIATE_CONTEXT_REMOVAL", "POST",
                        "/v1/admin/operations/events/" + OUTBOX_ID + "/retry",
                        Set.of(), "", Set.of(), false, NONE, 0),
                scenario("PS-A010", "POLICY_DRAFT_NO_PUBLISH", "PUT",
                        "/v1/admin/policies/" + POLICY_ID,
                        Set.of("ADMIN.APPROVAL_POLICY:UPDATE"), scoped(
                                "approvals.policy.update", "ADMIN.APPROVAL_POLICY:UPDATE"), Set.of(),
                        true, POLICY_DRAFT, 1),
                scenario("PS-A011", "SIGNATURE_READ_ONLY", "GET", "/v1/admin/signatures",
                        Set.of("ADMIN.APPROVAL_SIGNATURE:VIEW"), scoped(
                                "approvals.signature.read", "ADMIN.APPROVAL_SIGNATURE:VIEW"), Set.of(),
                        true, NONE, 0),
                scenario("PS-A012", "MASKED_READ_ONLY_OVERSIGHT", "GET",
                        "/v1/admin/signatures", Set.of("ADMIN.APPROVAL_SIGNATURE:VIEW"), "",
                        Set.of("TENANT_ADMIN"), true, NONE, 0),
                scenario("PS-A013", "BOUND_POLICY_PUBLISH_ONCE", "POST",
                        "/v1/admin/policies/" + POLICY_ID + "/publish",
                        Set.of("ADMIN.APPROVAL_POLICY:PUBLISH"), scoped(
                                "approvals.policy.publish", "ADMIN.APPROVAL_POLICY:PUBLISH"), Set.of(),
                        true, POLICY_PUBLISH, 1),
                scenario("PS-A014", "BOUND_FORM_PUBLISH_ONCE", "POST",
                        "/v1/admin/forms/" + FORM_ID + "/publish",
                        Set.of("ADMIN.APPROVAL_DESIGN:PUBLISH"), scoped(
                                "approvals.design.publish", "ADMIN.APPROVAL_DESIGN:PUBLISH"), Set.of(),
                        true, FORM_PUBLISH, 1),
                scenario("PS-A015", "TASK_CLAIM_ALLOWED", "POST",
                        "/v1/tasks/" + TASK_ID + "/claim", work, "",
                        Set.of("FINANCE_APPROVERS"), true, TASK_CLAIM, 1),
                scenario("PS-A016", "TASK_DECISION_ALLOWED_CLAIM_DENIED", "POST",
                        "/v1/tasks/" + TASK_ID + "/decisions", work, "", Set.of(),
                        true, TASK_DECIDE, 1),
                scenario("PS-A017", "SOD_CONFLICT", "POST",
                        "/v1/tasks/" + TASK_ID + "/decisions", work, "", Set.of(),
                        true, TASK_SELF_DENY, 0),
                scenario("PS-A018", "OWN_STATE_BOUND_ACTIONS_ONLY", "POST", "/v1/requests",
                        work, "", Set.of(), true, OWN_ACTIONS, 7));
    }

    private static Scenario scenario(
            String testId, String expected, String method, String path,
            Set<String> permissions, String resourceRoles, Set<String> roles,
            boolean allowed, Mutation mutation, int mutationCount) {
        return new Scenario(testId, expected, method, path, permissions, resourceRoles,
                roles, allowed, mutation, mutationCount);
    }

    private static void assertScenarioBoundaries(Scenario scenario, Harness harness) {
        switch (scenario.testId()) {
            case "PS-A001" -> assertDenied(harness, "POST", "/v1/admin/workflows",
                    scenario.permissions(), RESOURCE_ROLE, scenario.roles());
            case "PS-A002" -> assertDenied(harness, "POST",
                    "/v1/admin/workflows/" + WORKFLOW_ID + "/publish",
                    scenario.permissions(), RESOURCE_ROLE, scenario.roles());
            case "PS-A003" -> assertDenied(harness, "PUT",
                    "/v1/admin/workflows/" + WORKFLOW_ID + "/draft",
                    scenario.permissions(), RESOURCE_ROLE, scenario.roles());
            case "PS-A005" -> assertDenied(harness, "POST",
                    "/v1/admin/operations/events/" + OUTBOX_ID + "/retry",
                    scenario.permissions(), "", scenario.roles());
            case "PS-A006" -> assertDenied(harness, "GET", "/v1/home",
                    scenario.permissions(), RESOURCE_ROLE, scenario.roles());
            case "PS-A009" -> assertThat(harness.resolve(
                    "POST", scenario.path(), Set.of("ADMIN.APPROVAL_OPERATIONS:EXECUTE"),
                    scoped("approvals.operations.execute",
                            "ADMIN.APPROVAL_OPERATIONS:EXECUTE"), Set.of()).allowed()).isTrue();
            case "PS-A010" -> assertDenied(harness, "POST",
                    "/v1/admin/policies/" + POLICY_ID + "/publish",
                    scenario.permissions(), RESOURCE_ROLE, scenario.roles());
            case "PS-A011" -> assertDenied(harness, "POST", "/v1/admin/workflows",
                    scenario.permissions(), RESOURCE_ROLE, scenario.roles());
            case "PS-A012" -> {
                ApprovalPilotPepRegistry.Decision oversight = harness.resolve(
                        scenario.method(), scenario.path(), scenario.permissions(), "",
                        scenario.roles());
                assertThat(oversight.authorities()).singleElement().satisfies(authority -> {
                    assertThat(authority.profileKey()).isEqualTo("legacy-oversight");
                    assertThat(authority.readOnly()).isTrue();
                    assertThat(authority.projectionPolicyKey())
                            .isEqualTo("approvals.oversight.signature-metadata.v1");
                });
                assertDenied(harness, "POST", "/v1/admin/workflows",
                        scenario.permissions(), "", scenario.roles());
            }
            case "PS-A013" -> assertDenied(harness, "PUT",
                    "/v1/admin/policies/" + POLICY_ID,
                    scenario.permissions(), RESOURCE_ROLE, scenario.roles());
            default -> {
                // The action-bound cases are exercised through their owner-service mutations below.
            }
        }
    }

    private static void assertDenied(
            Harness harness, String method, String path, Set<String> permissions,
            String resourceRoles, Set<String> roles) {
        assertThat(harness.resolve(method, path, permissions, resourceRoles, roles).allowed())
                .isFalse();
    }

    private static String scoped(String contractKey, String resolvedCapabilityCode) {
        return RESOURCE_ROLE + ',' + ScopedAuthorityToken.wireToken(
                contractKey, resolvedCapabilityCode, "RS_APPROVALS");
    }

    private static String scopedAudit(String contractKey, String resolvedCapabilityCode) {
        return ScopedAuthorityToken.wireToken(
                contractKey, resolvedCapabilityCode, "RS_APPROVALS");
    }

    enum Mutation {
        NONE,
        DESIGN_DRAFT,
        WORKFLOW_PUBLISH,
        RECOVERY,
        POLICY_DRAFT,
        POLICY_PUBLISH,
        FORM_PUBLISH,
        TASK_CLAIM,
        TASK_DECIDE,
        TASK_SELF_DENY,
        OWN_ACTIONS
    }

    private record Scenario(
            String testId,
            String expectedOutcome,
            String method,
            String path,
            Set<String> permissions,
            String resourceRoles,
            Set<String> roles,
            boolean pepAllowed,
            Mutation mutation,
            int expectedMutationCount) {
        @Override
        public String toString() {
            return testId;
        }
    }

    private static final class Harness {
        private final ApprovalPilotPepRegistry registry = new ApprovalPilotPepRegistry(
                new ObjectMapper().findAndRegisterModules());
        private final ApprovalQueryRepository queries = mock(ApprovalQueryRepository.class);
        private final ApprovalCommandRepository commands = mock(ApprovalCommandRepository.class);
        private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
        private final ApprovalIdentityDirectory identities = mock(ApprovalIdentityDirectory.class);
        private final ApprovalOwnerPredicateEvaluator owner = mock(ApprovalOwnerPredicateEvaluator.class);
        private final ApprovalStepUpVerifier verifier = mock(ApprovalStepUpVerifier.class);
        private final ApprovalStepUpReplayRepository replay = mock(ApprovalStepUpReplayRepository.class);
        private final ApprovalHighRiskCommandGuard guard =
                new ApprovalHighRiskCommandGuard(verifier, replay, owner);
        private final ApprovalService service = new ApprovalService(
                queries, commands, audit, identities, guard, owner);

        private Harness() {
            when(verifier.payloadSha256(any())).thenReturn("fixture-payload-sha256");
            when(replay.reserve(any(), anyString(), anyString())).thenReturn(
                    ApprovalStepUpReplayRepository.Reservation.reserved(uuid(900)));
            when(verifier.verify(eq("signed-fixture-challenge"),
                    any(ApprovalStepUpVerifier.CommandBinding.class))).thenAnswer(invocation -> {
                        ApprovalStepUpVerifier.CommandBinding binding = invocation.getArgument(1);
                        return new ApprovalStepUpVerifier.VerifiedChallenge(
                                "fixture-challenge", "fixture-nonce", "fixture-auth",
                                binding, Instant.parse("2026-08-21T09:05:00Z"));
                    });
            when(queries.adminPulse(42L)).thenReturn(new ApprovalDtos.AdminPulse(
                    0, 0, 0, 0, 0, List.of()));
            when(queries.workflows(42L, false)).thenReturn(List.of());
            when(queries.policies(42L)).thenReturn(List.of());
            when(queries.delegations(any(ApprovalRequestContext.Actor.class)))
                    .thenReturn(List.of());
        }

        private ApprovalPilotPepRegistry.Decision resolve(
                String method, String path, Set<String> permissions,
                String resourceRoles, Set<String> roles) {
            return registry.authorize(new ApprovalPilotPepRegistry.RequestEvidence(
                    method, path, permissions, resourceRoles, roles,
                    canonicalRoute(method, path),
                    ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL));
        }

        private String canonicalRoute(String method, String path) {
            return registry.bindingContracts().stream()
                    .filter(binding -> method.equals(binding.method()))
                    .filter(binding -> matchesTemplate(binding.servicePath(), path))
                    .map(ApprovalPilotPepRegistry.BindingContract::routeContractKey)
                    .sorted()
                    .findFirst().orElse(null);
        }

        private boolean matchesTemplate(String template, String path) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\\{[A-Za-z][A-Za-z0-9]*}").matcher(template);
            StringBuilder expression = new StringBuilder("^");
            int offset = 0;
            while (matcher.find()) {
                expression.append(java.util.regex.Pattern.quote(
                        template.substring(offset, matcher.start()))).append("[^/]+");
                offset = matcher.end();
            }
            expression.append(java.util.regex.Pattern.quote(
                    template.substring(offset))).append('$');
            return path.matches(expression.toString());
        }

        private void execute(Mutation mutation) {
            switch (mutation) {
                case NONE -> {
                }
                case DESIGN_DRAFT -> designDrafts();
                case WORKFLOW_PUBLISH -> publishWorkflow();
                case RECOVERY -> retryDelivery();
                case POLICY_DRAFT -> updatePolicy();
                case POLICY_PUBLISH -> publishPolicy();
                case FORM_PUBLISH -> publishForm();
                case TASK_CLAIM -> claimTask();
                case TASK_DECIDE -> decideTask();
                case TASK_SELF_DENY -> denySelfDecision();
                case OWN_ACTIONS -> ownActions();
            }
        }

        private void designDrafts() {
            when(commands.createWorkflowDraft(any(), any())).thenReturn(WORKFLOW_ID);
            when(commands.createFormDraft(any(), any())).thenReturn(FORM_ID);
            ApprovalDtos.WorkflowStepInput step = new ApprovalDtos.WorkflowStepInput(
                    "REVIEW", "Review", "ANY", "FINANCE_APPROVERS", 60);
            service.createWorkflowDraft(new ApprovalDtos.CreateWorkflowDraftRequest(
                    "WF_FIXTURE", "워크플로", "Workflow", "설명", "Description",
                    "FINANCE", "INTERNAL", 60, "FINANCE", List.of(step)), "matrix");
            service.createFormDraft(new ApprovalDtos.CreateFormDraftRequest(
                    "FORM_FIXTURE", uuid(20), "양식", "Form", "설명", "Description",
                    "FINANCE", WORKFLOW_ID,
                    List.of(new ApprovalDtos.FormFieldInput(
                            "summary", "요약", "Summary", null, null,
                            "TEXT", true, List.of()))), "matrix");
        }

        private void publishWorkflow() {
            service.publishWorkflow(WORKFLOW_ID, 7L, "matrix", stepUp(7L));
            verify(owner).lockAndValidate(
                    ApprovalRequestContext.require(), "WORKFLOW", WORKFLOW_ID, 7L);
            verify(replay).consume(any(ApprovalStepUpVerifier.VerifiedChallenge.class));
        }

        private void retryDelivery() {
            service.retryIntegrationDelivery(OUTBOX_ID, 2L, "matrix", stepUp(2L));
            verify(owner).lockAndValidate(
                    ApprovalRequestContext.require(), "OUTBOX_EVENT", OUTBOX_ID, 2L);
            verify(replay).consume(any(ApprovalStepUpVerifier.VerifiedChallenge.class));
        }

        private void updatePolicy() {
            service.updatePolicy(POLICY_ID, new ApprovalDtos.UpdatePolicyRequest(
                    "BLOCK", "HIGH", "ACTIVE", Map.of("enabled", true),
                    "Fixture policy draft", 4L), "matrix");
        }

        private void publishPolicy() {
            service.publishPolicy(POLICY_ID, new ApprovalDtos.PublishPolicyRequest(
                    4L, "Fixture checker approval"), "matrix", stepUp(4L));
            verify(owner).lockAndValidate(
                    ApprovalRequestContext.require(), "POLICY", POLICY_ID, 4L);
            verify(replay).consume(any(ApprovalStepUpVerifier.VerifiedChallenge.class));
        }

        private void publishForm() {
            service.publishForm(FORM_ID, 5L, "matrix", stepUp(5L));
            verify(owner).lockAndValidate(
                    ApprovalRequestContext.require(), "FORM", FORM_ID, 5L);
            verify(replay).consume(any(ApprovalStepUpVerifier.VerifiedChallenge.class));
        }

        private void claimTask() {
            ApprovalQueryRepository.TaskAccess access = taskAccess(99L, null, "PENDING", 2L);
            when(queries.taskDetail(any(), eq(TASK_ID))).thenReturn(access);
            doThrow(new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE))
                    .when(owner).lockDecidableTask(any(), eq(access), eq(2L));

            service.claim(TASK_ID, 2L, "matrix");
            ApprovalRequestContext.Actor actor = ApprovalRequestContext.require();
            ApprovalPilotAuthorizationContext.set(resolve(
                    "POST", "/v1/tasks/" + TASK_ID + "/decisions",
                    actor.permissions(), "", actor.roles()).authorities());
            assertThatThrownBy(() -> service.decide(
                    TASK_ID, new ApprovalDtos.DecisionRequest("APPROVE", null, 2L), "matrix"))
                    .isInstanceOf(BaseException.class);
            verify(owner).lockClaimableTask(ApprovalRequestContext.require(), access, 2L);
        }

        private void decideTask() {
            ApprovalQueryRepository.TaskAccess access = taskAccess(99L, 17L, "PENDING", 3L);
            when(queries.taskDetail(any(), eq(TASK_ID))).thenReturn(access);
            when(commands.decide(any(), eq(access), any(), eq("matrix")))
                    .thenReturn(new ApprovalCommandRepository.DecisionResult("APPROVE", "IN_REVIEW"));
            doThrow(new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE))
                    .when(owner).lockClaimableTask(any(), eq(access), eq(3L));

            ApprovalPilotAuthorizationContext.set(resolve(
                    "POST", "/v1/tasks/" + TASK_ID + "/claim",
                    ApprovalRequestContext.require().permissions(), "",
                    ApprovalRequestContext.require().roles()).authorities());
            assertThatThrownBy(() -> service.claim(TASK_ID, 3L, "matrix"))
                    .isInstanceOf(BaseException.class);
            ApprovalPilotAuthorizationContext.set(resolve(
                    "POST", "/v1/tasks/" + TASK_ID + "/decisions",
                    ApprovalRequestContext.require().permissions(), "",
                    ApprovalRequestContext.require().roles()).authorities());
            service.decide(TASK_ID,
                    new ApprovalDtos.DecisionRequest("APPROVE", null, 3L), "matrix");
            verify(owner).lockDecidableTask(ApprovalRequestContext.require(), access, 3L);
        }

        private void denySelfDecision() {
            ApprovalQueryRepository.TaskAccess access = taskAccess(17L, 17L, "PENDING", 1L);
            when(queries.taskDetail(any(), eq(TASK_ID))).thenReturn(access);
            doThrow(new BaseException(ErrorCode.SOD_CONFLICT, "self decision"))
                    .when(owner).lockDecidableTask(any(), eq(access), eq(1L));

            assertThatThrownBy(() -> service.decide(
                    TASK_ID, new ApprovalDtos.DecisionRequest("APPROVE", null, 1L), "matrix"))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));
        }

        private void ownActions() {
            UUID draft = REQUEST_ID;
            UUID submitted = uuid(7);
            UUID needsInfo = uuid(8);
            UUID other = uuid(9);
            UUID delegation = uuid(10);
            when(commands.createDraft(any(), any(), anyString())).thenReturn(draft);
            when(commands.createDelegation(any(), any(), any())).thenReturn(
                    new ApprovalDelegationCommandSupport.Created(
                            delegation, "ALL", null, null));
            when(identities.require(42L, 23L)).thenReturn(new ApprovalIdentityDirectory.Subject(
                    42L, 23L, uuid(30), uuid(31), "Delegate", "delegate@example.com",
                    "Analyst", "ACTIVE", List.of("WORKSPACE_MEMBER")));

            service.create(new ApprovalDtos.CreateRequest(
                    WORKFLOW_ID, FORM_ID, "Fixture", "Fixture summary", "NORMAL", Map.of()),
                    "matrix");
            setRoute("PUT", "/v1/requests/" + draft + "/draft");
            service.updateDraft(draft, updateDraft(4L), "matrix");
            setRoute("POST", "/v1/requests/" + draft + "/submit");
            service.submit(draft, 4L, "matrix");
            setRoute("POST", "/v1/requests/" + submitted + "/withdraw");
            service.withdraw(submitted, 5L, "matrix");
            setRoute("POST", "/v1/requests/" + needsInfo + "/information-response");
            service.respondToInformationRequest(needsInfo,
                    new ApprovalDtos.InformationResponseRequest(
                            "Fixture information", Map.of("detail", "updated"), 6L), "matrix");
            setRoute("POST", "/v1/delegations");
            service.createDelegation(new ApprovalDtos.CreateDelegationRequest(
                    23L, "ALL", null, Instant.parse("2026-08-21T09:00:00Z"),
                    Instant.parse("2026-08-22T09:00:00Z"),
                    "Fixture delegation reason"), "matrix");
            setRoute("POST", "/v1/delegations/" + delegation + "/revoke");
            service.revokeDelegation(delegation, 3L, "matrix");

            setRoute("PUT", "/v1/requests/" + other + "/draft");
            doThrow(new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE, "other owner"))
                    .when(owner).lockOwnedRequest(any(), eq(other), eq(2L));
            assertThatThrownBy(() -> service.updateDraft(other, updateDraft(2L), "matrix"))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("other owner");
            verify(owner).requirePublishedForm(
                    ApprovalRequestContext.require(), FORM_ID, WORKFLOW_ID);
            verify(owner).lockOwnedDelegation(
                    ApprovalRequestContext.require(), delegation, 3L);
        }

        private void setRoute(String method, String path) {
            ApprovalRequestContext.Actor actor = ApprovalRequestContext.require();
            ApprovalPilotPepRegistry.Decision decision = resolve(
                    method, path, actor.permissions(), "", actor.roles());
            assertThat(decision.allowed()).isTrue();
            ApprovalPilotAuthorizationContext.set(decision.authorities());
        }

        private ApprovalDtos.UpdateDraftRequest updateDraft(long version) {
            return new ApprovalDtos.UpdateDraftRequest(
                    WORKFLOW_ID, FORM_ID, "Fixture", "Fixture summary", "NORMAL",
                    Map.of(), version);
        }

        private ApprovalQueryRepository.TaskAccess taskAccess(
                long requesterId, Long assigneeId, String status, long version) {
            ApprovalDtos.TaskSummary summary = new ApprovalDtos.TaskSummary(
                    TASK_ID, REQUEST_ID, "APR-FIXTURE", "Fixture", "Fixture",
                    "결재", "Approval", "REVIEW", "Review", 1, "Requester", "Finance",
                    status, "NORMAL", "INTERNAL", 1, Instant.parse("2026-08-21T08:00:00Z"),
                    Instant.parse("2026-08-22T08:00:00Z"), version);
            return new ApprovalQueryRepository.TaskAccess(
                    summary, requesterId, assigneeId,
                    assigneeId == null ? "FINANCE_APPROVERS" : null, false, null);
        }

        private ApprovalStepUpHeaders stepUp(long version) {
            return ApprovalStepUpHeaders.of(
                    "signed-fixture-challenge", "fixture-idempotency",
                    "fixture-decision-revision", version);
        }
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
