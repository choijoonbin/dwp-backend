package com.dwp.services.meeting.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingProductAccessPolicyTest {

    private final MeetingProductAccessPolicy policy = new MeetingProductAccessPolicy();

    @Test
    void exposesTheExactPMeetingsPageDataActionConsumerBindings() {
        assertThat(policy.bindingContracts())
                .hasSize(3)
                .extracting(MeetingProductAccessPolicy.BindingContract::routeKind)
                .containsExactly(
                        MeetingProductAccessPolicy.RouteKind.PAGE,
                        MeetingProductAccessPolicy.RouteKind.DATA,
                        MeetingProductAccessPolicy.RouteKind.ACTION);
        assertThat(policy.bindingContracts()).allSatisfy(binding -> {
            assertThat(binding.policyId()).isEqualTo("P-MEETINGS");
            assertThat(binding.productId()).isEqualTo("meetings");
            assertThat(binding.surfaceKey()).isEqualTo("meetings.work");
            assertThat(binding.ownerService()).isEqualTo("dwp-meeting-server");
            assertThat(binding.serviceKey()).isEqualTo("meeting");
            assertThat(binding.targetKind()).isEqualTo("SELF");
            assertThat(binding.gatewayPath()).startsWith("/api/meetings/v1/");
            assertThat(binding.servicePath()).startsWith("/v1/");
        });
        assertThat(policy.bindingContracts())
                .extracting(MeetingProductAccessPolicy.BindingContract::routeContractKey)
                .containsExactly(
                        "route.meetings.work.home.page",
                        "route.meetings.work.meetings.data",
                        "route.meetings.work.meeting-create.action");
    }

    @Test
    void normalAndElevatedUseTheSameEntitlementButSupportNeverInheritsIt() {
        String scope = policy.selfScope(7L, 19L);
        for (MeetingProductAccessPolicy.ActiveAccessMode mode : Set.of(
                MeetingProductAccessPolicy.ActiveAccessMode.NORMAL,
                MeetingProductAccessPolicy.ActiveAccessMode.ELEVATED)) {
            assertThat(policy.authorize(new MeetingProductAccessPolicy.RequestEvidence(
                    7L, 19L, "GET", "/v1/home",
                    "route.meetings.work.home.page", scope, mode, false,
                    Set.of("APP.MEETINGS:VIEW"))).allowed()).isTrue();
        }
        assertThat(policy.authorize(new MeetingProductAccessPolicy.RequestEvidence(
                7L, 19L, "GET", "/v1/home",
                "route.meetings.work.home.page", scope,
                MeetingProductAccessPolicy.ActiveAccessMode.PROVIDER_SUPPORT,
                true, Set.of("APP.MEETINGS:VIEW"))).allowed()).isFalse();
    }

    @Test
    void genericManageAndACrossTenantOpaqueScopeNeverSatisfyTheExactAction() {
        String foreignScope = policy.selfScope(8L, 19L);
        MeetingProductAccessPolicy.RequestEvidence evidence =
                new MeetingProductAccessPolicy.RequestEvidence(
                        7L, 19L, "POST", "/v1/meetings",
                        "route.meetings.work.meeting-create.action",
                        foreignScope,
                        MeetingProductAccessPolicy.ActiveAccessMode.NORMAL,
                        false, Set.of("APP.MEETINGS:MANAGE"));

        assertThat(policy.authorize(evidence)).satisfies(decision -> {
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reasonCode()).isEqualTo("TENANT_ACTOR_SCOPE_MISMATCH");
        });
    }
}
