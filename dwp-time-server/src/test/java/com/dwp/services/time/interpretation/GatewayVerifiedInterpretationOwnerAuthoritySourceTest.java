package com.dwp.services.time.interpretation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationAction;
import com.dwp.services.time.interpretation.InterpretationModels.PopulationKind;
import com.dwp.services.time.interpretation.InterpretationPopulationProjectionResolver.Member;
import com.dwp.services.time.interpretation.InterpretationPopulationProjectionResolver.PopulationProjection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class GatewayVerifiedInterpretationOwnerAuthoritySourceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T03:00:00Z");
    private static final UUID SUBJECT =
            UUID.fromString("73000000-0000-4000-8000-000000000001");
    private static final UUID POPULATION =
            UUID.fromString("73000000-0000-4000-8000-000000000002");
    private static final UUID WORKER =
            UUID.fromString("73000000-0000-4000-8000-000000000003");
    private static final UUID ASSIGNMENT =
            UUID.fromString("73000000-0000-4000-8000-000000000004");
    private static final UUID CORRELATION =
            UUID.fromString("73000000-0000-4000-8000-000000000005");
    private static final String DECISION = "psr-" + "a".repeat(64);
    private static final String CONTEXT = "psc-" + "b".repeat(64);
    private static final String ROLLOUT = "rollout-" + "c".repeat(64);

    private String activeScope;
    private final InterpretationPopulationProjectionResolver resolver =
            (tenant, actor, scope, checkedAt) -> Optional.of(projection(scope));
    private final GatewayVerifiedInterpretationOwnerAuthoritySource source =
            new GatewayVerifiedInterpretationOwnerAuthoritySource(
                    "time-service-token",
                    true,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    resolver);

    @Test
    void workbenchProjectsExactPopulationAndOnlyVerifiedRunAction() {
        MockHttpServletRequest request = request(
                InterpretationOwnerRoute.WORKBENCH,
                PopulationKind.LEGAL_ENTITY,
                "APP.HCM:VIEW,DATA.HR_TIME:VIEW,DATA.HR_TIME:UPDATE");
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.MASKED_FIELD_GROUPS_HEADER,
                "TRACE_SUMMARY");

        var verified = source.verify(
                request, InterpretationOwnerRoute.WORKBENCH).orElseThrow();

        assertThat(verified.actions()).containsExactlyInAnyOrder(
                InterpretationAction.VIEW, InterpretationAction.RUN);
        assertThat(verified.authorizedWorkerPublicIds()).containsExactly(WORKER);
        assertThat(verified.populationKind()).isEqualTo(PopulationKind.LEGAL_ENTITY);
        assertThat(verified.purposeCode())
                .isEqualTo(InterpretationIaProjectionService.VIEW_PURPOSE);
        assertThat(verified.maskedFieldGroups()).containsExactly("TRACE_SUMMARY");
        assertThat(verified.populationScopeDigest()).matches("[0-9a-f]{64}");
        assertThat(verified.decisionRevision()).isEqualTo(DECISION);
    }

    @Test
    void selfRunAcceptsPersonalSubmitAndPinsInterpretationPurpose() {
        MockHttpServletRequest request = request(
                InterpretationOwnerRoute.RUN,
                PopulationKind.SELF,
                "APP.HCM:VIEW,DATA.HR_TIME:CREATE");

        var verified = source.verify(request, InterpretationOwnerRoute.RUN).orElseThrow();

        assertThat(verified.actions()).containsExactly(InterpretationAction.RUN);
        assertThat(verified.purposeCode()).isEqualTo("TIME_INTERPRETATION");
        assertThat(verified.permissions()).contains("DATA.HR_TIME:CREATE");
    }

    @Test
    void selfPermissionCannotExpandIntoNonSelfPopulation() {
        MockHttpServletRequest request = request(
                InterpretationOwnerRoute.RUN,
                PopulationKind.TIME_GROUP,
                "APP.HCM:VIEW,DATA.HR_TIME:CREATE");

        assertThat(source.verify(request, InterpretationOwnerRoute.RUN)).isEmpty();
    }

    @Test
    void commandRequiresTheCurrentDecisionRevision() {
        MockHttpServletRequest request = request(
                InterpretationOwnerRoute.RUN,
                PopulationKind.SELF,
                "APP.HCM:VIEW,DATA.HR_TIME:CREATE");
        request.removeHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.EXPECTED_REVISION_HEADER);
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.EXPECTED_REVISION_HEADER,
                "psr-" + "f".repeat(64));

        assertThatThrownBy(() -> source.verify(request, InterpretationOwnerRoute.RUN))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.DECISION_REVISION_CONFLICT));
    }

    @Test
    void wrongRoutePurposeOrUnentitledDecisionFailsClosed() {
        MockHttpServletRequest wrongRoute = request(
                InterpretationOwnerRoute.TRACE,
                PopulationKind.SELF,
                "APP.HCM:VIEW,DATA.HR_TIME:VIEW");
        wrongRoute.removeHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.ROUTE_HEADER);
        wrongRoute.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.ROUTE_HEADER,
                InterpretationOwnerRoute.WORKBENCH.authorityContractKey());
        unavailable(() -> source.verify(wrongRoute, InterpretationOwnerRoute.TRACE));

        MockHttpServletRequest wrongPurpose = request(
                InterpretationOwnerRoute.RUN,
                PopulationKind.SELF,
                "APP.HCM:VIEW,DATA.HR_TIME:CREATE");
        wrongPurpose.removeHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.PURPOSE_HEADER);
        wrongPurpose.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.PURPOSE_HEADER,
                "TIME_OPERATIONS");
        unavailable(() -> source.verify(wrongPurpose, InterpretationOwnerRoute.RUN));

        MockHttpServletRequest noApp = request(
                InterpretationOwnerRoute.WORKBENCH,
                PopulationKind.SELF,
                "DATA.HR_TIME:VIEW");
        assertThat(source.verify(noApp, InterpretationOwnerRoute.WORKBENCH)).isEmpty();
    }

    @Test
    void malformedOrTotalFieldProjectionIsRejected() {
        MockHttpServletRequest request = request(
                InterpretationOwnerRoute.WORKBENCH,
                PopulationKind.SELF,
                "APP.HCM:VIEW,DATA.HR_TIME:VIEW");
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.OMITTED_FIELD_GROUPS_HEADER,
                "INTERPRETATION_RESULT,RULE_VERSION,TRACE_SUMMARY");

        unavailable(() -> source.verify(request, InterpretationOwnerRoute.WORKBENCH));
    }

    private MockHttpServletRequest request(
            InterpretationOwnerRoute route,
            PopulationKind populationKind,
            String permissions) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(route == InterpretationOwnerRoute.RUN ? "POST" : "GET");
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.SERVICE_TOKEN_HEADER,
                "time-service-token");
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.TENANT_HEADER, "41");
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.USER_HEADER, "72");
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.PERMISSIONS_HEADER,
                permissions);
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.ROUTE_HEADER,
                route.authorityContractKey());
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.CONTEXT_HEADER, CONTEXT);
        activeScope = HcmEligibilityScopeKey.derived(
                41L,
                72L,
                "hcm.operations.time-interpretation",
                route.bindingId(),
                "relationship-10",
                "population-10");
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.SCOPE_HEADER, activeScope);
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.ACCESS_MODE_HEADER, "NORMAL");
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.CURRENT_REVISION_HEADER,
                DECISION);
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.CURRENT_REVALIDATE_AT_HEADER,
                NOW.plusSeconds(60).toString());
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.ROLLOUT_STATE_HEADER, "110");
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.ROLLOUT_REVISION_HEADER,
                ROLLOUT);
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.ROLLOUT_COHORT_HEADER,
                "full");
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.CORRELATION_HEADER,
                CORRELATION.toString());
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.SUBJECT_HEADER,
                SUBJECT.toString());
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.FIELD_POLICY_REVISION_HEADER,
                "17");
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.AUTHORIZATION_REVISION_HEADER,
                "23");
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.POPULATION_KIND_HEADER,
                populationKind.name());
        request.addHeader(
                GatewayVerifiedInterpretationOwnerAuthoritySource.PURPOSE_HEADER,
                route.requiredPurpose());
        if (route.command()) {
            request.addHeader(
                    GatewayVerifiedInterpretationOwnerAuthoritySource.EXPECTED_REVISION_HEADER,
                    DECISION);
        }
        return request;
    }

    private PopulationProjection projection(String gatewayScopeKey) {
        assertThat(gatewayScopeKey).isEqualTo(activeScope);
        return new PopulationProjection(
                41,
                72,
                gatewayScopeKey,
                POPULATION,
                9,
                11,
                "d".repeat(64),
                "e".repeat(64),
                NOW.plusSeconds(300),
                List.of(new Member(WORKER, ASSIGNMENT, 7, 13, "f".repeat(64))));
    }

    private void unavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }
}
