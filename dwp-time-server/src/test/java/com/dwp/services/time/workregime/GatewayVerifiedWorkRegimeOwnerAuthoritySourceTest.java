package com.dwp.services.time.workregime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.time.workregime.WorkRegimeModels.Duty.TIME_AUDITOR;
import static com.dwp.services.time.workregime.WorkRegimeModels.Duty.TIME_CONFIG_APPROVER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayVerifiedWorkRegimeOwnerAuthoritySourceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T06:00:00Z");
    private static final String DECISION = "psr-" + "a".repeat(64);
    private static final String CONTEXT = "psc-" + "b".repeat(64);
    private static final String ROLLOUT = "rollout-" + "c".repeat(64);
    private static final String CORRELATION = "b4be4491-cf02-4b50-a3c4-2e59544e058b";
    private static final UUID POPULATION_ID =
            UUID.fromString("b4be4491-cf02-4b50-a3c4-2e59544e058c");
    private static final String STABLE_SCOPE =
            WorkRegimeTargetPopulationResolver.stableScopeRef(POPULATION_ID);
    private static final String GATEWAY_SCOPE = HcmEligibilityScopeKey.derived(
            7, 41, "hcm.operations", WorkRegimeOwnerRoute.LIST.scopeSource(),
            "relationship-r1", "target-population-r1");

    private final GatewayVerifiedWorkRegimeOwnerAuthoritySource source =
            new GatewayVerifiedWorkRegimeOwnerAuthoritySource(
                    "time-service-token", true,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    WorkRegimeTargetPopulationTestSupport.resolverAnyMember(
                            7L,
                            GATEWAY_SCOPE,
                            POPULATION_ID,
                            new WorkRegimeModels.EffectivePeriod(
                                    java.time.LocalDate.of(2026, 1, 1),
                                    java.time.LocalDate.of(2027, 1, 1)),
                            NOW));

    @Test
    void projectsExactReadRouteWithoutConsultingRoleLabels() {
        MockHttpServletRequest request = request(WorkRegimeOwnerRoute.LIST, "NORMAL");
        request.addHeader("X-DWP-Roles", "TENANT_DEFINED_UNRELATED_ROLE");

        WorkRegimeOwnerAuthoritySource.VerifiedRequest verified = source.verify(
                request, WorkRegimeOwnerRoute.LIST).orElseThrow();

        assertThat(verified.authority().tenantId()).isEqualTo(7);
        assertThat(verified.authority().actorId()).isEqualTo(41);
        assertThat(verified.authority().duties()).containsExactly(TIME_AUDITOR);
        assertThat(verified.authority().scopeRefs())
                .containsExactly(STABLE_SCOPE);
        assertThat(verified.authority().purpose())
                .isEqualTo(WorkRegimeLifecycleGuard.REQUIRED_PURPOSE);
        assertThat(verified.authority().decisionId()).isEqualTo(DECISION);
        assertThat(verified.revalidateAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void publishProjectsApproverDutyAndOnlyElevatedDecisionSatisfiesStepUp() {
        var normal = source.verify(
                request(WorkRegimeOwnerRoute.PUBLISH, "NORMAL"),
                WorkRegimeOwnerRoute.PUBLISH).orElseThrow();
        var elevated = source.verify(
                request(WorkRegimeOwnerRoute.PUBLISH, "ELEVATED"),
                WorkRegimeOwnerRoute.PUBLISH).orElseThrow();

        assertThat(normal.authority().duties()).containsExactly(TIME_CONFIG_APPROVER);
        assertThat(normal.authority().stepUpSatisfied()).isFalse();
        assertThat(elevated.authority().stepUpSatisfied()).isTrue();
    }

    @Test
    void missingExactCapabilityIsDeniedWithoutRoleFallback() {
        MockHttpServletRequest request = request(WorkRegimeOwnerRoute.CREATE_DRAFT, "NORMAL");
        request.removeHeader("X-DWP-Permissions");
        request.addHeader("X-DWP-Permissions", "APP.HCM:VIEW,DATA.HR_TIME:VIEW");
        request.addHeader("X-DWP-Roles", "TIME_CONFIG_AUTHOR");

        assertThat(source.verify(request, WorkRegimeOwnerRoute.CREATE_DRAFT)).isEmpty();
    }

    @Test
    void staleDecisionAndRouteOrScopeMismatchAreAuthorityUnavailable() {
        MockHttpServletRequest stale = request(WorkRegimeOwnerRoute.LIST, "NORMAL");
        stale.removeHeader("X-DWP-Current-Revalidate-At");
        stale.addHeader("X-DWP-Current-Revalidate-At", NOW.minusSeconds(1).toString());
        unavailable(() -> source.verify(stale, WorkRegimeOwnerRoute.LIST));

        MockHttpServletRequest wrongRoute = request(WorkRegimeOwnerRoute.LIST, "NORMAL");
        wrongRoute.removeHeader("X-DWP-Route-Contract-Key");
        wrongRoute.addHeader("X-DWP-Route-Contract-Key",
                WorkRegimeOwnerRoute.CREATE_DRAFT.routeContractKey());
        unavailable(() -> source.verify(wrongRoute, WorkRegimeOwnerRoute.LIST));

        MockHttpServletRequest wrongScope = request(WorkRegimeOwnerRoute.LIST, "NORMAL");
        wrongScope.removeHeader("X-DWP-Context-Scope-Key");
        wrongScope.addHeader("X-DWP-Context-Scope-Key", "hcm-scope-" + "d".repeat(40));
        unavailable(() -> source.verify(wrongScope, WorkRegimeOwnerRoute.LIST));
    }

    @Test
    void missingOrSpoofedGatewayIdentityIsAuthorityUnavailable() {
        MockHttpServletRequest missing = request(WorkRegimeOwnerRoute.LIST, "NORMAL");
        missing.removeHeader("X-DWP-Service-Token");
        unavailable(() -> source.verify(missing, WorkRegimeOwnerRoute.LIST));

        MockHttpServletRequest spoofed = request(WorkRegimeOwnerRoute.LIST, "NORMAL");
        spoofed.removeHeader("X-DWP-Service-Token");
        spoofed.addHeader("X-DWP-Service-Token", "browser-value");
        unavailable(() -> source.verify(spoofed, WorkRegimeOwnerRoute.LIST));
    }

    @Test
    void commandDecisionRevisionMustMatchGatewayCurrentRevision() {
        MockHttpServletRequest request = request(WorkRegimeOwnerRoute.SIMULATE, "NORMAL");
        request.removeHeader("X-DWP-Expected-Decision-Revision");
        request.addHeader("X-DWP-Expected-Decision-Revision", "psr-" + "e".repeat(64));

        assertThatThrownBy(() -> source.verify(request, WorkRegimeOwnerRoute.SIMULATE))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.DECISION_REVISION_CONFLICT));
    }

    @Test
    void missingOwnerProjectionFailsClosedEvenWithValidGatewayHeaders() {
        WorkRegimeTargetPopulationResolver resolver =
                mock(WorkRegimeTargetPopulationResolver.class);
        when(resolver.resolveActorAccess(
                anyLong(), anyLong(), any(String.class), any(Instant.class)))
                .thenReturn(Optional.empty());
        GatewayVerifiedWorkRegimeOwnerAuthoritySource unavailableSource =
                new GatewayVerifiedWorkRegimeOwnerAuthoritySource(
                        "time-service-token",
                        true,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        resolver);

        unavailable(() -> unavailableSource.verify(
                request(WorkRegimeOwnerRoute.LIST, "NORMAL"),
                WorkRegimeOwnerRoute.LIST));
    }

    @Test
    void mismatchedActorProjectionFailsClosedEvenWhenResolverReturnsARecord() {
        WorkRegimeTargetPopulationResolver resolver =
                mock(WorkRegimeTargetPopulationResolver.class);
        when(resolver.resolveActorAccess(
                anyLong(), anyLong(), any(String.class), any(Instant.class)))
                .thenReturn(Optional.of(
                        new WorkRegimeTargetPopulationResolver.PopulationAccess(
                                7L,
                                42L,
                                GATEWAY_SCOPE,
                                POPULATION_ID,
                                STABLE_SCOPE,
                                1L,
                                1L,
                                "a".repeat(64),
                                "b".repeat(64),
                                NOW.plusSeconds(300))));
        GatewayVerifiedWorkRegimeOwnerAuthoritySource mismatchedSource =
                new GatewayVerifiedWorkRegimeOwnerAuthoritySource(
                        "time-service-token",
                        true,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        resolver);

        unavailable(() -> mismatchedSource.verify(
                request(WorkRegimeOwnerRoute.LIST, "NORMAL"),
                WorkRegimeOwnerRoute.LIST));
    }

    private MockHttpServletRequest request(
            WorkRegimeOwnerRoute route, String accessMode) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-DWP-Service-Token", "time-service-token");
        request.addHeader("X-DWP-Tenant-ID", "7");
        request.addHeader("X-DWP-User-ID", "41");
        request.addHeader("X-DWP-Permissions", "APP.HCM:VIEW," + route.capability());
        request.addHeader("X-DWP-Route-Contract-Key", route.routeContractKey());
        request.addHeader("X-DWP-Context-Key", CONTEXT);
        request.addHeader("X-DWP-Context-Scope-Key", scope(route));
        request.addHeader("X-DWP-Active-Access-Mode", accessMode);
        request.addHeader("X-DWP-Current-Decision-Revision", DECISION);
        request.addHeader("X-DWP-Current-Revalidate-At", NOW.plusSeconds(60).toString());
        request.addHeader("X-DWP-Rollout-State", "110");
        request.addHeader("X-DWP-Rollout-Revision", ROLLOUT);
        request.addHeader("X-DWP-Rollout-Cohort", "full");
        request.addHeader("X-Correlation-ID", CORRELATION);
        if (route.command()) {
            request.addHeader("X-DWP-Expected-Decision-Revision", DECISION);
        }
        return request;
    }

    private String scope(WorkRegimeOwnerRoute route) {
        return HcmEligibilityScopeKey.derived(
                7, 41, "hcm.operations", route.scopeSource(),
                "relationship-r1", "target-population-r1");
    }

    private void unavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }
}
