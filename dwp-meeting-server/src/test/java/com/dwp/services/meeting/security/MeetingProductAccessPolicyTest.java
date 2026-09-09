package com.dwp.services.meeting.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingProductAccessPolicyTest {

    private final MeetingProductAccessPolicy policy = new MeetingProductAccessPolicy();

    @Test
    void exposesTheExactPMeetingsPageDataActionConsumerBindings() {
        assertThat(policy.bindingContracts())
                .hasSize(5)
                .extracting(MeetingProductAccessPolicy.BindingContract::routeKind)
                .containsExactly(
                        MeetingProductAccessPolicy.RouteKind.PAGE,
                        MeetingProductAccessPolicy.RouteKind.DATA,
                        MeetingProductAccessPolicy.RouteKind.ACTION,
                        MeetingProductAccessPolicy.RouteKind.ACTION,
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
                        "route.meetings.work.meeting-create.action",
                        "route.meetings.work.participant-disconnect.action",
                        "route.meetings.work.intelligence-report-export.action");
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

    @Test
    void disconnectRequiresTheDedicatedRouteContractAndUpdateCapability() {
        String path = "/v1/meetings/00000000-0000-4000-8000-000000000001"
                + "/participants/00000000-0000-4000-8000-000000000002/disconnect";
        String scope = policy.selfScope(7L, 19L);

        assertThat(policy.authorize(new MeetingProductAccessPolicy.RequestEvidence(
                7L, 19L, "POST", path,
                "route.meetings.work.participant-disconnect.action", scope,
                MeetingProductAccessPolicy.ActiveAccessMode.NORMAL, false,
                Set.of("APP.MEETINGS:UPDATE"))).allowed()).isTrue();
        assertThat(policy.authorize(new MeetingProductAccessPolicy.RequestEvidence(
                7L, 19L, "POST", path,
                "route.meetings.work.meeting-create.action", scope,
                MeetingProductAccessPolicy.ActiveAccessMode.NORMAL, false,
                Set.of("APP.MEETINGS:UPDATE"))).reasonCode())
                .isEqualTo("ROUTE_CONTRACT_MISMATCH");
        assertThat(policy.authorize(new MeetingProductAccessPolicy.RequestEvidence(
                7L, 19L, "POST", path,
                "route.meetings.work.participant-disconnect.action", scope,
                MeetingProductAccessPolicy.ActiveAccessMode.NORMAL, false,
                Set.of("APP.MEETINGS:CREATE"))).reasonCode())
                .isEqualTo("EXACT_ROUTE_AUTHORITY_REQUIRED");
    }

    @Test
    void intelligenceExportRequiresItsDedicatedRouteContractAndUpdateCapability() {
        String path = "/v1/meetings/00000000-0000-4000-8000-000000000001"
                + "/intelligence/reports/00000000-0000-4000-8000-000000000003/exports";
        String scope = policy.selfScope(7L, 19L);

        assertThat(policy.authorize(new MeetingProductAccessPolicy.RequestEvidence(
                7L, 19L, "POST", path,
                "route.meetings.work.intelligence-report-export.action", scope,
                MeetingProductAccessPolicy.ActiveAccessMode.NORMAL, false,
                Set.of("APP.MEETINGS:UPDATE"))).allowed()).isTrue();
        assertThat(policy.authorize(new MeetingProductAccessPolicy.RequestEvidence(
                7L, 19L, "POST", path,
                "route.meetings.work.participant-disconnect.action", scope,
                MeetingProductAccessPolicy.ActiveAccessMode.NORMAL, false,
                Set.of("APP.MEETINGS:UPDATE"))).reasonCode())
                .isEqualTo("ROUTE_CONTRACT_MISMATCH");
    }

    @Test
    void dynamicMutationBindingsRejectTemplatesMalformedIdsAndSiblingPaths() {
        String disconnect = "/v1/meetings/00000000-0000-4000-8000-000000000001"
                + "/participants/00000000-0000-4000-8000-000000000002/disconnect";
        String export = "/v1/meetings/00000000-0000-4000-8000-000000000001"
                + "/intelligence/reports/00000000-0000-4000-8000-000000000003/exports";

        assertThat(policy.ownsCandidate("POST", disconnect)).isTrue();
        assertThat(policy.ownsCandidate("POST", export)).isTrue();
        assertThat(policy.ownsCandidate("GET", disconnect)).isFalse();
        assertThat(policy.ownsCandidate("POST", disconnect + "/extra")).isFalse();
        assertThat(policy.ownsCandidate("POST", export + "/extra")).isFalse();
        assertThat(policy.ownsCandidate("POST", export.replace(
                "00000000-0000-4000-8000-000000000003", "not-a-uuid"))).isFalse();
        assertThat(policy.ownsCandidate(
                "POST",
                "/v1/meetings/{meetingId}/participants/{participantId}/disconnect"))
                .isFalse();
        assertThat(policy.ownsCandidate(
                "POST",
                "/v1/meetings/{meetingId}/intelligence/reports/{reportId}/exports"))
                .isFalse();
    }
}
