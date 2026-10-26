package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductAuthorizationV62ContractTest {
    private static final String CAPABILITY =
            "approvals.work.hris-separation-waiver-task.approve";
    private static final String ROUTE =
            "route.approvals.work.hris-separation-waiver-task-approve.action";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ProductAuthorizationContractValidator validator =
            new ProductAuthorizationContractValidator(mapper);

    @Test
    void sealsTheDedicatedHrm013ApprovalCapabilityAndRoute() throws Exception {
        assertThat(ProductSurfaceStepUpRouteResolver.supportsSealedRuntimeBundle(62L)).isTrue();
        assertThat(ProductSurfaceStepUpRouteResolver.supportsSealedRuntimeBundle(61L)).isFalse();
        ProductAuthorizationContractDtos.BundleContract contract = loadContract();
        assertThat(contract.version()).isEqualTo(62);
        assertThat(contract.bundleStatus()).isEqualTo("DRAFT");
        assertThat(contract.checksum()).isEqualTo(
                "cb370fd02a896452a64187fca8afb8bb3e3d281e490fba02e76f7a3c0d7f9770");
        assertThat(contract.capabilities()).hasSize(280);
        assertThat(contract.accessPolicies()).hasSize(24);
        assertThat(contract.entitlementExpressions()).hasSize(17);
        assertThat(contract.predicatePolicies()).hasSize(87);
        assertThat(contract.routes()).hasSize(1158);

        assertThat(contract.capabilities()).filteredOn(value ->
                CAPABILITY.equals(value.contractKey())).singleElement()
                .satisfies(capability -> {
                    assertThat(capability.productKey()).isEqualTo("approvals");
                    assertThat(capability.surfaceKey()).isEqualTo("approvals.work");
                    assertThat(capability.resolvedCapabilityCode())
                            .isEqualTo("ACTION.APPROVAL_TASK:APPROVE");
                    assertThat(capability.resourceKey()).isEqualTo("ACTION.APPROVAL_TASK");
                    assertThat(capability.action()).isEqualTo("APPROVE");
                    assertThat(capability.authorityMode()).isEqualTo("PERMISSION");
                    assertThat(capability.responsibilityRequirement())
                            .isEqualTo("NOT_REQUIRED");
                    assertThat(capability.requiredResponsibilityCode()).isNull();
                    assertThat(capability.scopeResolver())
                            .isEqualTo("DECIDABLE_TASK_SELF_APPROVAL_SOD");
                    assertThat(capability.riskTier()).isEqualTo("HIGH");
                    assertThat(capability.activationPolicy())
                            .isEqualTo("STEPUP-MGMT-HIGH-V1");
                    assertThat(capability.sodPolicyId()).isNull();
                    assertThat(capability.requiresProductEntitlement()).isTrue();
                    assertThat(capability.owner()).isEqualTo("Approvals + Security");
                    assertThat(capability.routeContractKeys()).containsExactly(ROUTE);
                });

        assertThat(contract.routes()).filteredOn(value ->
                ROUTE.equals(value.routeContractKey())).singleElement()
                .satisfies(route -> {
                    assertThat(route.navigationContextId()).isEqualTo("approvals.work");
                    assertThat(route.subject().type()).isEqualTo("PRODUCT");
                    assertThat(route.subject().productKey()).isEqualTo("approvals");
                    assertThat(route.subject().surfaceKey()).isEqualTo("approvals.work");
                    assertThat(route.routeKind()).isEqualTo("ACTION");
                    assertThat(route.sideEffectFree()).isNull();
                    assertThat(route.uiRouteId()).isNull();
                    assertThat(route.uiRoutePattern()).isNull();
                    assertThat(route.accessProfiles()).singleElement().satisfies(profile -> {
                        assertThat(profile.profileKey()).isEqualTo("full-work");
                        assertThat(profile.precedence()).isEqualTo(300);
                        assertThat(profile.activeAccessModes())
                                .containsExactly("NORMAL", "ELEVATED");
                        assertThat(profile.requiredAccess().capabilityContractKey())
                                .isEqualTo(CAPABILITY);
                        assertThat(profile.targetBindingKinds()).containsExactly("OBJECT");
                        assertThat(profile.predicatePolicyKeys()).containsExactly(
                                "predicate.approval-task-decision.v1",
                                "predicate.approval.object-version.v1");
                        assertThat(profile.readOnly()).isFalse();
                    });
                    assertThat(route.gatewayApiBindings()).singleElement()
                            .satisfies(binding -> {
                                assertThat(binding.bindingKey())
                                        .isEqualTo(ROUTE + ".binding.01");
                                assertThat(binding.method()).isEqualTo("POST");
                                assertThat(binding.path()).isEqualTo(
                                        "/api/approvals/v1/hris/"
                                                + "separation-waiver-tasks/{taskId}/approve");
                            });
                    assertThat(route.servicePepBindings()).singleElement()
                            .satisfies(binding -> {
                                assertThat(binding.bindingKey())
                                        .isEqualTo(ROUTE + ".binding.01");
                                assertThat(binding.method()).isEqualTo("POST");
                                assertThat(binding.path()).isEqualTo(
                                        "/v1/hris/separation-waiver-tasks/{taskId}/approve");
                                assertThat(binding.serviceKey()).isEqualTo("approval");
                            });
                    assertThat(route.stepUpCommandBindings()).singleElement()
                            .satisfies(binding -> {
                                assertThat(binding.bindingKey())
                                        .isEqualTo(ROUTE + ".binding.01");
                                assertThat(binding.targetType())
                                        .isEqualTo("HRIS_SEPARATION_WAIVER_TASK");
                                assertThat(binding.targetIdPathParameter()).isEqualTo("taskId");
                                assertThat(binding.expectedObjectVersionSource())
                                        .isEqualTo("COMMAND_HEADER");
                                assertThat(binding.expectedObjectVersionName())
                                        .isEqualTo("X-DWP-Expected-Object-Version");
                                assertThat(binding.ownerServiceKey()).isEqualTo("approval");
                                assertThat(binding.audience()).isEqualTo("dwp-approval-server");
                            });
                });
    }

    @Test
    void resolverAcceptsOnlyTheExactHrm013ApproveDescriptor() throws Exception {
        Descriptor descriptor = descriptor(loadContract());
        var resolver = new ProductSurfaceStepUpRouteResolver(null);

        assertThat(resolver.validateHrm013ApproveDescriptor(
                descriptor.route(), descriptor.profile(), descriptor.capability(),
                descriptor.gateway(), descriptor.stepUp()))
                .isEqualTo("STEPUP-MGMT-HIGH-V1");

        ObjectNode changedOwner = mapper.valueToTree(descriptor.route());
        changedOwner.put("owner", "Generic Approvals");
        assertDescriptorRejected(resolver, descriptor, changedOwner);

        ObjectNode duplicateGateway = mapper.valueToTree(descriptor.route());
        duplicateGateway.withArray("gatewayApiBindings")
                .add(duplicateGateway.withArray("gatewayApiBindings").get(0).deepCopy());
        assertDescriptorRejected(resolver, descriptor, duplicateGateway);

        ObjectNode duplicateStepUp = mapper.valueToTree(descriptor.route());
        duplicateStepUp.withArray("stepUpCommandBindings")
                .add(duplicateStepUp.withArray("stepUpCommandBindings").get(0).deepCopy());
        assertDescriptorRejected(resolver, descriptor, duplicateStepUp);
    }

    private void assertDescriptorRejected(
            ProductSurfaceStepUpRouteResolver resolver,
            Descriptor descriptor,
            ObjectNode changedRoute) throws Exception {
        ProductAuthorizationContractDtos.GovernedRoute route = mapper.treeToValue(
                changedRoute, ProductAuthorizationContractDtos.GovernedRoute.class);
        assertThatThrownBy(() -> resolver.validateHrm013ApproveDescriptor(
                route, descriptor.profile(), descriptor.capability(),
                descriptor.gateway(), descriptor.stepUp()))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);
    }

    private ProductAuthorizationContractDtos.BundleContract loadContract() throws Exception {
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v62.generated.json")
                .getInputStream()) {
            return validator.validateDocument(input);
        }
    }

    private Descriptor descriptor(ProductAuthorizationContractDtos.BundleContract contract) {
        var route = contract.routes().stream()
                .filter(value -> ROUTE.equals(value.routeContractKey()))
                .findFirst().orElseThrow();
        var capability = contract.capabilities().stream()
                .filter(value -> CAPABILITY.equals(value.contractKey()))
                .findFirst().orElseThrow();
        return new Descriptor(route, route.accessProfiles().getFirst(), capability,
                route.gatewayApiBindings().getFirst(),
                route.stepUpCommandBindings().getFirst());
    }

    private record Descriptor(
            ProductAuthorizationContractDtos.GovernedRoute route,
            ProductAuthorizationContractDtos.AccessProfile profile,
            ProductAuthorizationContractDtos.CapabilityContract capability,
            ProductAuthorizationContractDtos.GatewayBinding gateway,
            ProductAuthorizationContractDtos.StepUpCommandBinding stepUp) {
    }
}
