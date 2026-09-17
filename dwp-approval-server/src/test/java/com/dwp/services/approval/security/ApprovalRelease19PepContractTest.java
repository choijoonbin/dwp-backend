package com.dwp.services.approval.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRelease19PepContractTest {
    private static final String REQUEST = "14d7b229-4752-4a50-8ac1-ecc129620649";
    private static final String PREFLIGHT_ROUTE =
            "route.approvals.work.request-preflight.action";
    private static final String SUBMIT_ROUTE =
            "route.approvals.work.request-submit.action";
    private static final Set<String> UPDATE = Set.of("ACTION.APPROVAL_REQUEST:UPDATE");

    private final ApprovalPilotPepRegistry registry =
            new ApprovalPilotPepRegistry(new ObjectMapper().findAndRegisterModules());

    @Test
    void preflightHasAnIndependentExactOwnerMutationBinding() {
        var decision = authorize("/v1/requests/" + REQUEST + "/preflight", PREFLIGHT_ROUTE);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.authorities()).singleElement().satisfies(authority -> {
            assertThat(authority.capabilityContractKey())
                    .isEqualTo("approvals.work.request.update");
            assertThat(authority.predicatePolicyKeys()).containsExactlyInAnyOrder(
                    "predicate.approval.own-request.v1",
                    "predicate.approval.object-version.v1");
        });
        assertThat(registry.bindingContracts()).hasSize(247);
    }

    @Test
    void preflightAndSubmitCannotBorrowEachOthersRouteAuthority() {
        assertThat(authorize("/v1/requests/" + REQUEST + "/preflight", SUBMIT_ROUTE).allowed())
                .isFalse();
        assertThat(authorize("/v1/requests/" + REQUEST + "/submit", PREFLIGHT_ROUTE).allowed())
                .isFalse();
    }

    private ApprovalPilotPepRegistry.Decision authorize(String path, String route) {
        return registry.authorize(new ApprovalPilotPepRegistry.RequestEvidence(
                "POST",
                path,
                UPDATE,
                "",
                Set.of(),
                route,
                ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL));
    }
}
