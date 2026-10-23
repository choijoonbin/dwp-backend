package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAuthorizationV59ContractTest {
    private static final String CHECKSUM =
            "56582749cebf382c5c2669d83a30fb4c81fcb532db2878b15e6a83ced6898094";
    private static final Set<String> ROUTES = Set.of(
            "route.hcm.team.performance.page",
            "route.hcm.team.performance.data",
            "route.hcm.personal.performance-feedback.page",
            "route.hcm.performance.feedback-request-options.data",
            "route.hcm.performance.feedback-request-create.action",
            "route.hcm.personal.feedback-assignments-mine.data",
            "route.hcm.personal.feedback-assignment-detail.data",
            "route.hcm.personal.feedback-response-submit.action",
            "route.hcm.performance.feedback-release.data");

    private final ProductAuthorizationContractValidator validator =
            new ProductAuthorizationContractValidator(
                    new ObjectMapper().findAndRegisterModules());

    @Test
    void sealsTheExactPer008CapabilityPredicateAndRouteDelta() throws Exception {
        ProductAuthorizationContractDtos.BundleContract contract;
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v59.generated.json")
                .getInputStream()) {
            contract = validator.validateDocument(input);
        }

        assertThat(contract.version()).isEqualTo(59);
        assertThat(contract.bundleStatus()).isEqualTo("DRAFT");
        assertThat(contract.checksum()).isEqualTo(CHECKSUM);
        assertThat(contract.capabilities()).hasSize(276);
        assertThat(contract.accessPolicies()).hasSize(24);
        assertThat(contract.entitlementExpressions()).hasSize(17);
        assertThat(contract.predicatePolicies()).hasSize(86);
        assertThat(contract.routes()).hasSize(1134);

        Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities =
                contract.capabilities().stream().collect(Collectors.toMap(
                        ProductAuthorizationContractDtos.CapabilityContract::contractKey,
                        Function.identity()));
        assertCapability(capabilities.get("hcm.team.workbench.view"),
                "DATA.HRIS_TEAM_WORKBENCH:VIEW", "hcm.team",
                "TEAM_TARGET_POPULATION", "HIGH", null);
        assertCapability(capabilities.get("hcm.personal.performance.view"),
                "DATA.HR_TALENT:VIEW", "hcm.personal", "SELF", "MEDIUM", null);
        assertCapability(capabilities.get("per.feedback.request.create"),
                "ACTION.HRIS_PERFORMANCE_FEEDBACK:CREATE", "hcm.personal",
                "SELF", "MEDIUM", null);
        assertCapability(capabilities.get("per.feedback.assigned.submit"),
                "ACTION.HRIS_PERFORMANCE_FEEDBACK:UPDATE", "hcm.personal",
                "FEEDBACK_ASSIGNMENT", "HIGH", "NO_FEEDBACK_OUTSIDE_ASSIGNMENT");
        assertCapability(capabilities.get("per.feedback.aggregate.view"),
                "DATA.HRIS_PERFORMANCE_FEEDBACK:VIEW", "hcm.personal",
                "FEEDBACK_RELEASE_POPULATION", "HIGH",
                "NO_FEEDBACK_RELEASE_OUTSIDE_POLICY");

        Map<String, ProductAuthorizationContractDtos.PredicatePolicy> predicates =
                contract.predicatePolicies().stream().collect(Collectors.toMap(
                        ProductAuthorizationContractDtos.PredicatePolicy::predicatePolicyKey,
                        Function.identity()));
        assertPredicate(predicates.get("predicate.hcm-performance-feedback-request.v1"),
                List.of("SELF", "OBJECT", "TARGET_POPULATION"),
                "PerformanceFeedbackRequestAuthorityEvidenceV1");
        assertPredicate(predicates.get("predicate.hcm-performance-feedback-assignment.v1"),
                List.of("OBJECT", "TARGET_POPULATION"),
                "PerformanceFeedbackAssignmentAuthorityEvidenceV1");
        assertPredicate(predicates.get("predicate.hcm-performance-feedback-release.v1"),
                List.of("SELF", "OBJECT", "TARGET_POPULATION"),
                "PerformanceFeedbackReleaseAuthorityEvidenceV1");

        Map<String, ProductAuthorizationContractDtos.GovernedRoute> routes =
                contract.routes().stream().collect(Collectors.toMap(
                        ProductAuthorizationContractDtos.GovernedRoute::routeContractKey,
                        Function.identity()));
        assertThat(routes.keySet()).containsAll(ROUTES);
        for (String key : ROUTES) {
            var route = routes.get(key);
            assertThat(route.servicePepBindings()).singleElement()
                    .extracting(ProductAuthorizationContractDtos.ServicePepBinding::serviceKey)
                    .isEqualTo("people");
            assertThat(route.stepUpCommandBindings()).isNullOrEmpty();
        }
        var options = routes.get("route.hcm.performance.feedback-request-options.data");
        assertThat(options.accessProfiles()).extracting(
                ProductAuthorizationContractDtos.AccessProfile::profileKey)
                .containsExactly("manager-feedback-requester", "self-feedback-requester");
        assertThat(options.gatewayApiBindings()).singleElement().satisfies(binding ->
                assertThat(binding.queryParameterConstraints()).containsOnlyKeys(
                        "cycleVersionId", "subjectWorkerPublicId",
                        "subjectAssignmentPublicId"));
        var release = routes.get("route.hcm.performance.feedback-release.data");
        assertThat(release.accessProfiles()).extracting(
                ProductAuthorizationContractDtos.AccessProfile::profileKey)
                .containsExactly("self-feedback-release-reader",
                        "manager-feedback-release-reader",
                        "operator-feedback-release-reader");
        assertThat(release.accessProfiles().getFirst().targetBindingKinds())
                .containsExactly("SELF", "OBJECT");
    }

    private void assertCapability(
            ProductAuthorizationContractDtos.CapabilityContract capability,
            String code, String surface, String scope, String risk, String sod) {
        assertThat(capability).isNotNull();
        assertThat(capability.resolvedCapabilityCode()).isEqualTo(code);
        assertThat(capability.surfaceKey()).isEqualTo(surface);
        assertThat(capability.scopeResolver()).isEqualTo(scope);
        assertThat(capability.riskTier()).isEqualTo(risk);
        assertThat(capability.activationPolicy()).isNull();
        assertThat(capability.sodPolicyId()).isEqualTo(sod);
        assertThat(capability.authorityMode()).isEqualTo("PERMISSION_AND_RELATIONSHIP");
        assertThat(capability.requiresProductEntitlement()).isTrue();
    }

    private void assertPredicate(
            ProductAuthorizationContractDtos.PredicatePolicy predicate,
            List<String> targetKinds, String evidenceSchema) {
        assertThat(predicate).isNotNull();
        assertThat(predicate.ownerServiceKey()).isEqualTo("people");
        assertThat(predicate.targetBindingKinds()).containsExactlyElementsOf(targetKinds);
        assertThat(predicate.inputEvidenceSchemaKey()).isEqualTo(evidenceSchema);
    }
}
