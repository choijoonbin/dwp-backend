package com.dwp.services.approval.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ApprovalDtosBinaryCompatibilityTest {

    private static final Set<String> PUBLISHED_NESTED_TYPES = Set.of(
            "AdminPulse",
            "ApprovalMetrics",
            "AssuranceSignal",
            "ContentAccess",
            "CreateDelegationRequest",
            "CreateFormCategoryRequest",
            "CreateFormDraftRequest",
            "CreateRequest",
            "CreateWorkflowDraftRequest",
            "DecisionInsight",
            "DecisionRequest",
            "DelegationCandidate",
            "DelegationSummary",
            "FormCategorySummary",
            "FormDetail",
            "FormFieldInput",
            "FormRouteSummary",
            "FormSummary",
            "HomeResponse",
            "InformationResponseRequest",
            "IntegrationDeliverySummary",
            "OperationSignal",
            "OperationsResponse",
            "PolicySummary",
            "PolicyVersionSummary",
            "PublishFormRequest",
            "PublishPolicyRequest",
            "PublishWorkflowRequest",
            "QuorumInformationSnapshot",
            "QuorumTaskSnapshot",
            "QuorumVotePrecondition",
            "RequestDetail",
            "RequestPreflight",
            "RequestPreflightCheck",
            "RequestSummary",
            "RequestTemplate",
            "RetryEligibility",
            "SignatureCapabilities",
            "SignatureProviderSummary",
            "StageMetric",
            "TaskDetail",
            "TaskSummary",
            "TimelineEvent",
            "UpdateDraftRequest",
            "UpdateFormCategoryRequest",
            "UpdateFormDraftRequest",
            "UpdatePolicyRequest",
            "UpdateWorkflowDraftRequest",
            "VersionedActionRequest",
            "WorkflowDetail",
            "WorkflowRuntimePins",
            "WorkflowStepInput",
            "WorkflowSummary");

    @Test
    void preservesPublishedNestedJvmNames() {
        Set<String> actual = Arrays.stream(ApprovalDtos.class.getDeclaredClasses())
                .filter(type -> Modifier.isPublic(type.getModifiers()))
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());

        assertThat(actual).isEqualTo(PUBLISHED_NESTED_TYPES);
        PUBLISHED_NESTED_TYPES.forEach(name -> assertDoesNotThrow(
                () -> Class.forName(ApprovalDtos.class.getName() + '$' + name)));
    }
}
