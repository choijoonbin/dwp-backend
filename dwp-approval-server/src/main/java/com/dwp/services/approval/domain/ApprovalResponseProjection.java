package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalPilotAuthorizationContext;
import com.dwp.services.approval.security.ApprovalPilotPepRegistry;
import com.dwp.services.approval.security.ApprovalProjectionSchemaContract;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** Applies the server-selected registry projection before serialization. */
@Component
public final class ApprovalResponseProjection {

    public Object overview(ApprovalDtos.AdminPulse value) {
        Profile profile = profile(Set.of("route.approvals.admin.overview.page"));
        if (profile.full()) return value;
        profile.require("legacy-oversight", "approvals.oversight.summary.v1",
                "ApprovalOversightAdminPulseV1");
        return new ApprovalProjectionDtos.OversightAdminPulseV1(
                value.publishedWorkflows(), value.draftWorkflows(), value.activeRequests(),
                value.overdueTasks(), value.failedIntegrations(), value.assurance().stream()
                .map(signal -> new ApprovalProjectionDtos.OversightAssuranceSignalV1(
                        signal.key(), signal.state(), signal.exceptions()))
                .toList());
    }

    public Object workflows(List<ApprovalDtos.WorkflowSummary> values) {
        Profile profile = workflowProfile();
        if (profile.full()) return values;
        requireWorkflowMask(profile);
        return values.stream().map(this::workflow).toList();
    }

    public Object workflow(ApprovalDtos.WorkflowDetail value) {
        Profile profile = workflowProfile();
        if (profile.full()) return value;
        requireWorkflowMask(profile);
        return workflow(value.workflow());
    }

    public Object forms(List<ApprovalDtos.FormSummary> values) {
        Profile profile = formProfile();
        if (profile.full()) return values;
        requireFormMask(profile);
        return values.stream().map(this::form).toList();
    }

    public Object form(ApprovalDtos.FormDetail value) {
        Profile profile = formProfile();
        if (profile.full()) return value;
        requireFormMask(profile);
        return form(value.form());
    }

    public Object formCategories(List<ApprovalDtos.FormCategorySummary> values) {
        Profile profile = formProfile();
        if (profile.full()) return values;
        requireFormMask(profile);
        return values.stream().map(value ->
                new ApprovalProjectionDtos.OversightFormCategoryV1(
                        value.categoryId(), value.categoryKey(), value.parentCategoryId(),
                        value.nameKo(), value.nameEn(), value.iconKey(), value.sortOrder(),
                        value.lifecycleState(), value.formCount(), value.version()))
                .toList();
    }

    public Object policies(List<ApprovalDtos.PolicySummary> values) {
        Profile profile = policyProfile();
        if (profile.full()) return values;
        requirePolicyMask(profile);
        return values.stream().map(value -> new ApprovalProjectionDtos.OversightPolicyV1(
                value.policyId(), value.policyKey(), value.nameKo(), value.nameEn(),
                value.policyType(), value.enforcementMode(), value.severity(),
                value.lifecycleState(), value.version(), value.pendingReview(),
                value.pendingEnforcementMode(), value.pendingSeverity(),
                value.pendingLifecycleState(), value.pendingAt())).toList();
    }

    public Object policyVersions(List<ApprovalDtos.PolicyVersionSummary> values) {
        Profile profile = policyProfile();
        if (profile.full()) return values;
        requirePolicyMask(profile);
        return values.stream().map(value ->
                new ApprovalProjectionDtos.OversightPolicyVersionV1(
                        value.policyVersionId(), value.versionNumber(), value.enforcementMode(),
                        value.severity(), value.lifecycleState(), value.submittedAt(),
                        value.publishedAt())).toList();
    }

    public Object operations(ApprovalDtos.OperationsResponse value) {
        Profile profile = profile(Set.of("route.approvals.admin.operations.page"));
        if (profile.full()) return value;
        if (profile.matches("auditor", "approvals.audit.operations.v1",
                "ApprovalAuditorOperationsV1")) {
            return new ApprovalProjectionDtos.AuditorOperationsV1(
                    value.generatedAt(), value.signals().stream().map(signal ->
                    new ApprovalProjectionDtos.AuditorOperationSignalV1(
                            signal.key(), signal.state(), signal.count())).toList(),
                    value.integrationDeliveries().stream().map(delivery ->
                    new ApprovalProjectionDtos.AuditorIntegrationDeliveryV1(
                            delivery.eventType(), delivery.status(), delivery.attemptCount(),
                            delivery.manualRetryCount(), delivery.availableAt(),
                            delivery.publishedAt())).toList());
        }
        profile.require("legacy-oversight", "approvals.oversight.operations.v1",
                "ApprovalOversightOperationsV1");
        return new ApprovalProjectionDtos.OversightOperationsV1(
                value.generatedAt(), value.signals().stream().map(signal ->
                new ApprovalProjectionDtos.OversightOperationSignalV1(
                        signal.key(), signal.state(), signal.titleKo(), signal.titleEn(),
                        signal.count())).toList(),
                value.integrationDeliveries().stream().map(delivery ->
                new ApprovalProjectionDtos.OversightIntegrationDeliveryV1(
                        delivery.outboxId(), delivery.eventType(), delivery.status(),
                        delivery.attemptCount(), delivery.manualRetryCount(),
                        delivery.availableAt(), delivery.publishedAt(), delivery.createdAt(),
                        delivery.lastRetriedAt())).toList());
    }

    public Object signatures(List<ApprovalDtos.SignatureProviderSummary> values) {
        Profile profile = profile(Set.of("route.approvals.admin.signatures.page"));
        if (profile.legacyCompatibility()) return values;
        if (profile.full()) {
            return values.stream().map(value ->
                    new ApprovalProjectionDtos.FullManagementSignatureV1(
                            value.providerId(), value.providerKey(), value.displayName(),
                            value.providerType(), value.lifecycleState(),
                            value.credentialConfigured(), value.lastHealthCheckedAt(),
                            value.version())).toList();
        }
        profile.require("legacy-oversight", "approvals.oversight.signature-metadata.v1",
                "ApprovalOversightSignatureV1");
        return values.stream().map(value -> new ApprovalProjectionDtos.OversightSignatureV1(
                value.providerId(), value.providerKey(), value.displayName(), value.providerType(),
                value.lifecycleState(), value.credentialConfigured(),
                value.lastHealthCheckedAt(), value.version())).toList();
    }

    private Profile workflowProfile() {
        return profile(Set.of(
                "route.approvals.admin.workflows.page",
                "route.approvals.admin.forms-workflow-reference.data"));
    }

    private Profile formProfile() {
        return profile(Set.of("route.approvals.admin.forms.page"));
    }

    private Profile policyProfile() {
        return profile(Set.of("route.approvals.admin.policies.page"));
    }

    private void requireWorkflowMask(Profile profile) {
        profile.require("legacy-oversight", "approvals.oversight.workflow-metadata.v1",
                "ApprovalOversightWorkflowV1");
    }

    private void requireFormMask(Profile profile) {
        profile.require("legacy-oversight", "approvals.oversight.form-metadata.v1",
                "ApprovalOversightFormV1");
    }

    private void requirePolicyMask(Profile profile) {
        profile.require("legacy-oversight", "approvals.oversight.policy-metadata.v1",
                "ApprovalOversightPolicyV1");
    }

    private ApprovalProjectionDtos.OversightWorkflowV1 workflow(
            ApprovalDtos.WorkflowSummary value) {
        return new ApprovalProjectionDtos.OversightWorkflowV1(
                value.workflowId(), value.workflowKey(), value.nameKo(), value.nameEn(),
                value.category(), value.dataClassification(), value.lifecycleState(),
                value.currentVersion(), value.slaMinutes(), value.version(), value.updatedAt());
    }

    private ApprovalProjectionDtos.OversightFormV1 form(ApprovalDtos.FormSummary value) {
        return new ApprovalProjectionDtos.OversightFormV1(
                value.formId(), value.formKey(), value.categoryId(), value.categoryKey(),
                value.categoryNameKo(), value.categoryNameEn(), value.nameKo(), value.nameEn(),
                value.formKind(), value.lifecycleState(), value.currentVersion(),
                value.fieldCount(), value.routeCount(), value.usageCount(), value.version(),
                value.updatedAt());
    }

    private Profile profile(Set<String> expectedRoutes) {
        List<ApprovalPilotPepRegistry.RouteAuthority> authorities =
                ApprovalPilotAuthorizationContext.current().orElse(List.of());
        ApprovalDecisionRevisionContext.Evidence evidence =
                ApprovalDecisionRevisionContext.current().orElse(null);
        if (authorities.isEmpty() && evidence == null) return Profile.legacyFull();
        if (evidence == null || !("110".equals(evidence.rolloutState())
                || "111".equals(evidence.rolloutState()))
                || evidence.validUntil() == null
                || !evidence.validUntil().isAfter(java.time.OffsetDateTime.now())
                || evidence.revision() == null || evidence.revision().isBlank()
                || evidence.contextKey() == null || evidence.contextKey().isBlank()
                || evidence.contextScopeKey() == null || evidence.contextScopeKey().isBlank()
                || !expectedRoutes.contains(evidence.routeContractKey())) {
            throw unavailable();
        }
        List<ApprovalPilotPepRegistry.RouteAuthority> matches = authorities.stream()
                .filter(authority -> evidence.routeContractKey().equals(
                        authority.routeContractKey()))
                .toList();
        if (matches.size() != 1) throw unavailable();
        ApprovalPilotPepRegistry.RouteAuthority authority = matches.getFirst();
        if ("full-management".equals(authority.profileKey())) {
            String route = authority.routeContractKey();
            if (!(route + ".full-management.projection.v1")
                    .equals(authority.projectionPolicyKey())
                    || !(route + ".response.v1").equals(authority.responseSchemaKey())) {
                throw unavailable();
            }
            if (!ApprovalProjectionSchemaContract.metadataAbsent(
                    authority.projectionSchemaVersion(),
                    authority.openApiSchemaSha256(),
                    authority.projectionAdditionalProperties())) {
                throw unavailable();
            }
        }
        return new Profile(
                authority.profileKey(), authority.projectionPolicyKey(),
                authority.responseSchemaKey(), authority.projectionSchemaVersion(),
                authority.openApiSchemaSha256(),
                authority.projectionAdditionalProperties(), false);
    }

    private BaseException unavailable() {
        return new BaseException(
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "The registered Approval response projection is unavailable.");
    }

    private record Profile(
            String key, String projectionPolicyKey, String responseSchemaKey,
            Integer schemaVersion, String openApiSchemaSha256,
            Boolean additionalProperties,
            boolean legacyCompatibility) {

        static Profile legacyFull() {
            return new Profile(null, null, null, null, null, null, true);
        }

        boolean full() {
            return legacyCompatibility || "full-management".equals(key);
        }

        boolean matches(String expectedKey, String expectedProjection, String expectedSchema) {
            return expectedKey.equals(key)
                    && expectedProjection.equals(projectionPolicyKey)
                    && expectedSchema.equals(responseSchemaKey)
                    && ApprovalProjectionSchemaContract.matches(
                            expectedKey, expectedSchema, schemaVersion,
                            openApiSchemaSha256, additionalProperties);
        }

        void require(String expectedKey, String expectedProjection, String expectedSchema) {
            if (!matches(expectedKey, expectedProjection, expectedSchema)) {
                throw new BaseException(
                        ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "The Approval response projection does not match the registered schema.");
            }
        }
    }
}
