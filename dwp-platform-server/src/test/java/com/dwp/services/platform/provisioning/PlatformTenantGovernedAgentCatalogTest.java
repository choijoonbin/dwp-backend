package com.dwp.services.platform.provisioning;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformTenantGovernedAgentCatalogTest {

    @Test
    void provisionsTheReferencePlannerWithEveryAgentRuntimeTenant() {
        assertThat(PlatformTenantProvisioningService.governedAgents(
                List.of("ai.agent-runtime")))
                .extracting(PlatformTenantProvisioningService.AgentSeed::entryKey)
                .containsExactly("REFERENCE_PLANNER", "DWP_ASSISTANT");
    }

    @Test
    void approvalExpertRemainsEntitlementBound() {
        assertThat(PlatformTenantProvisioningService.governedAgents(
                List.of("ai.agent-runtime", "core.approvals")))
                .extracting(PlatformTenantProvisioningService.AgentSeed::entryKey)
                .containsExactly(
                        "REFERENCE_PLANNER", "DWP_ASSISTANT", "DWP_APPROVAL_EXPERT");
    }
}
