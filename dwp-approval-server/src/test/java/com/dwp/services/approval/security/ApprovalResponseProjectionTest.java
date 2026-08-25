package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalResponseProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalResponseProjectionTest {

    private static final String REVISION = "psr-"
            + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-08-24T04:00:00Z");

    private final ApprovalResponseProjection projection = new ApprovalResponseProjection();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clearContexts() {
        ApprovalPilotAuthorizationContext.clear();
        ApprovalDecisionRevisionContext.clear();
    }

    @Test
    void legacyOversightSerializesOnlyTheRegisteredWorkflowAndFormFields() {
        useProfile(
                "route.approvals.admin.workflows.page",
                "legacy-oversight",
                "approvals.oversight.workflow-metadata.v1",
                "ApprovalOversightWorkflowV1");

        JsonNode workflow = json.valueToTree(projection.workflow(workflowDetail()));
        assertFields(workflow, Set.of(
                "workflowId", "workflowKey", "nameKo", "nameEn", "category",
                "dataClassification", "lifecycleState", "currentVersion", "slaMinutes",
                "version", "updatedAt"));
        assertThat(workflow.has("descriptionKo")).isFalse();
        assertThat(workflow.has("ownerGroupRef")).isFalse();
        assertThat(workflow.has("definition")).isFalse();
        assertThat(workflow.has("definitionHash")).isFalse();

        clearContexts();
        useProfile(
                "route.approvals.admin.forms.page",
                "legacy-oversight",
                "approvals.oversight.form-metadata.v1",
                "ApprovalOversightFormV1");

        JsonNode form = json.valueToTree(projection.form(formDetail()));
        assertFields(form, Set.of(
                "formId", "formKey", "categoryId", "categoryKey", "categoryNameKo",
                "categoryNameEn", "nameKo", "nameEn", "formKind", "lifecycleState",
                "currentVersion", "fieldCount", "routeCount", "usageCount", "version",
                "updatedAt"));
        assertThat(form.has("descriptionKo")).isFalse();
        assertThat(form.has("ownerGroupRef")).isFalse();
        assertThat(form.has("schema")).isFalse();
        assertThat(form.has("schemaHash")).isFalse();
        assertThat(form.has("routes")).isFalse();

        JsonNode category = json.valueToTree(projection.formCategories(
                List.of(formCategory())).getClass().cast(projection.formCategories(
                List.of(formCategory()))));
        assertFields(category.get(0), Set.of(
                "categoryId", "categoryKey", "parentCategoryId", "nameKo", "nameEn",
                "iconKey", "sortOrder", "lifecycleState", "formCount", "version"));
        assertThat(category.get(0).has("descriptionKo")).isFalse();
    }

    @Test
    void legacyOversightSerializesOnlyRegisteredPolicyAndSignatureFields() {
        useProfile(
                "route.approvals.admin.policies.page",
                "legacy-oversight",
                "approvals.oversight.policy-metadata.v1",
                "ApprovalOversightPolicyV1");

        JsonNode policy = json.valueToTree(projection.policies(List.of(policy())).getClass()
                .cast(projection.policies(List.of(policy()))));
        assertFields(policy.get(0), Set.of(
                "policyId", "policyKey", "nameKo", "nameEn", "policyType",
                "enforcementMode", "severity", "lifecycleState", "version",
                "pendingReview", "pendingEnforcementMode", "pendingSeverity",
                "pendingLifecycleState", "pendingAt"));
        assertThat(policy.get(0).has("rule")).isFalse();
        assertThat(policy.get(0).has("pendingRule")).isFalse();
        assertThat(policy.get(0).has("pendingChangeReason")).isFalse();
        assertThat(policy.get(0).has("pendingBy")).isFalse();

        JsonNode version = json.valueToTree(projection.policyVersions(
                List.of(policyVersion())).getClass().cast(projection.policyVersions(
                List.of(policyVersion()))));
        assertFields(version.get(0), Set.of(
                "policyVersionId", "versionNumber", "enforcementMode", "severity",
                "lifecycleState", "submittedAt", "publishedAt"));
        assertThat(version.get(0).has("rule")).isFalse();
        assertThat(version.get(0).has("changeReason")).isFalse();
        assertThat(version.get(0).has("reviewComment")).isFalse();
        assertThat(version.get(0).has("submittedBy")).isFalse();
        assertThat(version.get(0).has("publishedBy")).isFalse();

        clearContexts();
        useProfile(
                "route.approvals.admin.signatures.page",
                "legacy-oversight",
                "approvals.oversight.signature-metadata.v1",
                "ApprovalOversightSignatureV1");
        JsonNode signature = json.valueToTree(projection.signatures(
                List.of(signature())).getClass().cast(projection.signatures(
                List.of(signature()))));
        assertFields(signature.get(0), Set.of(
                "providerId", "providerKey", "displayName", "providerType",
                "lifecycleState", "credentialConfigured", "lastHealthCheckedAt", "version"));
        assertThat(signature.get(0).has("capabilities")).isFalse();
    }

    @Test
    void oversightAndAuditorOperationsUseDifferentClosedSchemas() {
        ApprovalDtos.OperationsResponse source = operations();
        useProfile(
                "route.approvals.admin.operations.page",
                "legacy-oversight",
                "approvals.oversight.operations.v1",
                "ApprovalOversightOperationsV1");

        JsonNode oversight = json.valueToTree(projection.operations(source));
        assertFields(oversight, Set.of("generatedAt", "signals", "integrationDeliveries"));
        assertFields(oversight.path("signals").get(0),
                Set.of("key", "state", "titleKo", "titleEn", "count"));
        assertFields(oversight.path("integrationDeliveries").get(0), Set.of(
                "outboxId", "eventType", "status", "attemptCount", "manualRetryCount",
                "availableAt", "publishedAt", "createdAt", "lastRetriedAt"));
        assertThat(oversight.has("breachedTasks")).isFalse();
        assertThat(oversight.toString()).doesNotContain(
                "eventId", "requestId", "lastError", "failure-secret");

        clearContexts();
        useProfile(
                "route.approvals.admin.operations.page",
                "auditor",
                "approvals.audit.operations.v1",
                "ApprovalAuditorOperationsV1");

        JsonNode auditor = json.valueToTree(projection.operations(source));
        assertFields(auditor, Set.of("generatedAt", "signals", "integrationDeliveries"));
        assertFields(auditor.path("signals").get(0), Set.of("key", "state", "count"));
        assertFields(auditor.path("integrationDeliveries").get(0), Set.of(
                "eventType", "status", "attemptCount", "manualRetryCount",
                "availableAt", "publishedAt"));
        assertThat(auditor.toString()).doesNotContain(
                "outboxId", "eventId", "requestId", "lastError", "failure-secret",
                "titleKo", "titleEn", "detailKo", "detailEn");
    }

    @Test
    void overviewUsesTheClosedSummarySchema() {
        useProfile(
                "route.approvals.admin.overview.page",
                "legacy-oversight",
                "approvals.oversight.summary.v1",
                "ApprovalOversightAdminPulseV1");

        JsonNode value = json.valueToTree(projection.overview(new ApprovalDtos.AdminPulse(
                1, 2, 3, 4, 5,
                List.of(new ApprovalDtos.AssuranceSignal("sod", "HEALTHY", 0)))));
        assertFields(value, Set.of(
                "publishedWorkflows", "draftWorkflows", "activeRequests", "overdueTasks",
                "failedIntegrations", "assurance"));
        assertFields(value.path("assurance").get(0), Set.of("key", "state", "exceptions"));
    }

    @Test
    void missingDuplicateWrongRouteOrUnknownProjectionFailsClosed() {
        ApprovalDecisionRevisionContext.set(
                REVISION, OffsetDateTime.now().plusMinutes(5), "context", "scope",
                "route.approvals.admin.workflows.page", "111");
        assertUnavailable(() -> projection.workflows(List.of(workflow())));

        clearContexts();
        ApprovalPilotPepRegistry.RouteAuthority valid = authority(
                "route.approvals.admin.workflows.page", "legacy-oversight",
                "approvals.oversight.workflow-metadata.v1", "ApprovalOversightWorkflowV1");
        ApprovalPilotAuthorizationContext.set(List.of(valid, valid));
        setDecision("route.approvals.admin.workflows.page");
        assertUnavailable(() -> projection.workflows(List.of(workflow())));

        clearContexts();
        useProfile(
                "route.approvals.admin.forms.page", "legacy-oversight",
                "approvals.oversight.form-metadata.v1", "ApprovalOversightFormV1");
        assertUnavailable(() -> projection.workflows(List.of(workflow())));

        clearContexts();
        useProfile(
                "route.approvals.admin.workflows.page", "legacy-oversight",
                "unknown.projection.v1", "ApprovalOversightWorkflowV1");
        assertUnavailable(() -> projection.workflows(List.of(workflow())));

        clearContexts();
        useProfile(
                "route.approvals.admin.workflows.page", "legacy-oversight",
                "approvals.oversight.workflow-metadata.v1", "UnknownWorkflowSchemaV1");
        assertUnavailable(() -> projection.workflows(List.of(workflow())));
    }

    @Test
    void missingOrMismatchedProjectionSchemaMetadataFailsClosed() {
        String route = "route.approvals.admin.workflows.page";
        List<ApprovalPilotPepRegistry.RouteAuthority> invalid = List.of(
                new ApprovalPilotPepRegistry.RouteAuthority(
                        route, "PAGE", "legacy-oversight", true, Set.of(), null,
                        null, null, false,
                        "approvals.oversight.workflow-metadata.v1",
                        "ApprovalOversightWorkflowV1"),
                new ApprovalPilotPepRegistry.RouteAuthority(
                        route, "PAGE", "legacy-oversight", true, Set.of(), null,
                        null, null, false,
                        "approvals.oversight.workflow-metadata.v1",
                        "ApprovalOversightWorkflowV1", 2,
                        ApprovalProjectionSchemaContract.expectedSha256(
                                "ApprovalOversightWorkflowV1"), false),
                new ApprovalPilotPepRegistry.RouteAuthority(
                        route, "PAGE", "legacy-oversight", true, Set.of(), null,
                        null, null, false,
                        "approvals.oversight.workflow-metadata.v1",
                        "ApprovalOversightWorkflowV1", 1, "0".repeat(64), false),
                new ApprovalPilotPepRegistry.RouteAuthority(
                        route, "PAGE", "legacy-oversight", true, Set.of(), null,
                        null, null, false,
                        "approvals.oversight.workflow-metadata.v1",
                        "ApprovalOversightWorkflowV1", 1,
                        ApprovalProjectionSchemaContract.expectedSha256(
                                "ApprovalOversightWorkflowV1"), true));

        for (ApprovalPilotPepRegistry.RouteAuthority authority : invalid) {
            clearContexts();
            ApprovalPilotAuthorizationContext.set(List.of(authority));
            setDecision(route);
            assertUnavailable(() -> projection.workflows(List.of(workflow())));
        }
    }

    @Test
    void legacyCompatibilityAndExactFullManagementKeepTheDomainDto() {
        ApprovalDtos.WorkflowDetail value = workflowDetail();
        assertThat(projection.workflow(value)).isSameAs(value);

        useProfile(
                "route.approvals.admin.workflows.page", "full-management",
                "route.approvals.admin.workflows.page.full-management.projection.v1",
                "route.approvals.admin.workflows.page.response.v1");
        assertThat(projection.workflow(value)).isSameAs(value);

        clearContexts();
        useProfile(
                "route.approvals.admin.workflows.page", "full-management",
                "route.approvals.admin.workflows.page.full-management.projection.v1",
                "wrong.response.v1");
        assertUnavailable(() -> projection.workflow(value));
    }

    @Test
    void fullManagementSignatureReadStillMasksCredentialCapabilities() {
        useProfile(
                "route.approvals.admin.signatures.page", "full-management",
                "route.approvals.admin.signatures.page.full-management.projection.v1",
                "route.approvals.admin.signatures.page.response.v1");

        JsonNode result = json.valueToTree(projection.signatures(List.of(signature())));
        assertFields(result.get(0), Set.of(
                "providerId", "providerKey", "displayName", "providerType",
                "lifecycleState", "credentialConfigured", "lastHealthCheckedAt", "version"));
        assertThat(result.toString()).doesNotContain("capabilities", "credentialReference",
                "secret-ref");
    }

    private void useProfile(
            String route, String profile, String projectionKey, String responseSchemaKey) {
        ApprovalPilotAuthorizationContext.set(List.of(
                authority(route, profile, projectionKey, responseSchemaKey)));
        setDecision(route);
    }

    private void setDecision(String route) {
        ApprovalDecisionRevisionContext.set(
                REVISION, OffsetDateTime.now().plusMinutes(5), "context", "scope", route, "111");
    }

    private ApprovalPilotPepRegistry.RouteAuthority authority(
            String route, String profile, String projectionKey, String responseSchemaKey) {
        String schemaHash = ApprovalProjectionSchemaContract.expectedSha256(responseSchemaKey);
        return new ApprovalPilotPepRegistry.RouteAuthority(
                route, "PAGE", profile, true, Set.of(), null,
                null, null, false, projectionKey, responseSchemaKey,
                schemaHash == null ? null : 1, schemaHash,
                schemaHash == null ? null : false);
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    private void assertFields(JsonNode value, Set<String> expected) {
        assertThat(value).isNotNull();
        assertThat(value.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(expected);
    }

    private ApprovalDtos.WorkflowSummary workflow() {
        return new ApprovalDtos.WorkflowSummary(
                UUID.randomUUID(), "EXPENSE", "비용", "Expense", "secret-ko", "secret-en",
                "FINANCE", "CONFIDENTIAL", "PUBLISHED", 3, 120, false,
                "GROUP_SECRET", 9L, NOW);
    }

    private ApprovalDtos.WorkflowDetail workflowDetail() {
        return new ApprovalDtos.WorkflowDetail(
                workflow(), Map.of("steps", List.of("secret-step")), "definition-secret");
    }

    private ApprovalDtos.FormSummary form() {
        return new ApprovalDtos.FormSummary(
                UUID.randomUUID(), "EXPENSE_FORM", UUID.randomUUID(), "FINANCE",
                "재무", "Finance", "비용 신청", "Expense request", "secret-ko", "secret-en",
                "FORM_OWNER_SECRET", "REQUEST", "PUBLISHED", 2, 10, 2, 31L, 7L, NOW);
    }

    private ApprovalDtos.FormDetail formDetail() {
        return new ApprovalDtos.FormDetail(
                form(), Map.of("fields", List.of("secret-field")), "schema-secret",
                List.of(new ApprovalDtos.FormRouteSummary(
                        UUID.randomUUID(), UUID.randomUUID(), "EXPENSE", "비용", "Expense",
                        "PUBLISHED", 3, 120, "DEFAULT", 1)));
    }

    private ApprovalDtos.FormCategorySummary formCategory() {
        return new ApprovalDtos.FormCategorySummary(
                UUID.randomUUID(), "FINANCE", null, "재무", "Finance",
                "secret-ko", "secret-en", "coins", 10, "ACTIVE", 3, 4L);
    }

    private ApprovalDtos.PolicySummary policy() {
        return new ApprovalDtos.PolicySummary(
                UUID.randomUUID(), "BLOCK_SELF", "본인 결재 금지", "Block self approval",
                "SOD", "BLOCK", "HIGH", "PUBLISHED", Map.of("secret", true), 5L,
                true, "WARN", "MEDIUM", "DRAFT", Map.of("secret", false),
                "pending reason secret", 99L, NOW);
    }

    private ApprovalDtos.PolicyVersionSummary policyVersion() {
        return new ApprovalDtos.PolicyVersionSummary(
                UUID.randomUUID(), 2, "BLOCK", "HIGH", "PUBLISHED",
                Map.of("secret", true), "change reason secret", 91L, NOW,
                92L, NOW.plusSeconds(60), "review secret");
    }

    private ApprovalDtos.OperationsResponse operations() {
        ApprovalDtos.TaskSummary breached = new ApprovalDtos.TaskSummary(
                UUID.randomUUID(), UUID.randomUUID(), "REQ-SECRET", "Task", "secret summary",
                "결재", "Approval", "REVIEW", "Review", 1, "Secret Person",
                "Secret Org", "PENDING", "HIGH", "CONFIDENTIAL", 99,
                NOW, NOW.plusSeconds(3600), 10L);
        ApprovalDtos.IntegrationDeliverySummary delivery =
                new ApprovalDtos.IntegrationDeliverySummary(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "REQUEST_CREATED",
                        "FAILED", 3, 1, NOW, null, "failure-secret", NOW, NOW, 8L);
        return new ApprovalDtos.OperationsResponse(
                NOW, List.of(new ApprovalDtos.OperationSignal(
                "outbox", "ATTENTION", "전달", "Delivery", "secret-ko", "secret-en", 1)),
                List.of(breached), List.of(delivery));
    }

    private ApprovalDtos.SignatureProviderSummary signature() {
        return new ApprovalDtos.SignatureProviderSummary(
                UUID.randomUUID(), "DOCUSIGN", "DocuSign", "REMOTE", "ACTIVE",
                Map.of("credentialReference", "secret-ref"), true, NOW, 2L);
    }
}
