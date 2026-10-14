package com.dwp.services.time.timecard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.services.time.timecard.TimecardModels.PopulationKind;
import com.dwp.services.time.timecard.TimecardModels.TimecardAction;
import com.dwp.services.time.timecard.TimecardPopulationProjectionResolver.Member;
import com.dwp.services.time.timecard.TimecardPopulationProjectionResolver.PopulationProjection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class GatewayVerifiedTimecardOwnerAuthoritySourceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    private static final UUID SUBJECT = uuid("17000000-0000-4000-8000-000000000001");
    private static final UUID POPULATION = uuid("17000000-0000-4000-8000-000000000002");
    private static final UUID WORKER = uuid("17000000-0000-4000-8000-000000000003");
    private static final UUID ACTOR_WORKER =
            uuid("17000000-0000-4000-8000-000000000013");
    private static final UUID ASSIGNMENT = uuid("17000000-0000-4000-8000-000000000004");
    private static final UUID CORRELATION = uuid("17000000-0000-4000-8000-000000000005");
    private static final String DECISION = "psr-" + "a".repeat(64);
    private static final String CONTEXT = "psc-" + "b".repeat(64);
    private static final String ROLLOUT = "rollout-" + "c".repeat(64);

    private String activeScope;
    private UUID currentActorWorker;
    private final TimecardPopulationProjectionResolver resolver =
            (tenant, actor, scope, checkedAt) -> Optional.of(projection(scope));
    private final GatewayVerifiedTimecardOwnerAuthoritySource source =
            new GatewayVerifiedTimecardOwnerAuthoritySource(
                    "time-service-token", true,
                    Clock.fixed(NOW, ZoneOffset.UTC), resolver,
                    (tenant, actor, subject, checkedAt) -> Optional.of(
                            new TimecardActorWorkerResolver.ActorWorkerEvidence(
                                    tenant, actor, subject, currentActorWorker,
                                    checkedAt, NOW.plusSeconds(300))));

    @Test
    void selfQueryUsesViewAndTargetQueryUsesUpdateOrApprove() {
        var self = source.verify(request(
                TimecardOwnerRoute.QUERY, PopulationKind.SELF,
                "APP.HCM:VIEW,DATA.HR_TIME:VIEW"), TimecardOwnerRoute.QUERY)
                .orElseThrow();
        assertThat(self.actions()).containsExactly(TimecardAction.VIEW);
        assertThat(self.authorizedWorkerPublicIds()).containsExactly(WORKER);

        assertThat(source.verify(request(
                TimecardOwnerRoute.QUERY, PopulationKind.SELF,
                "APP.HCM:VIEW,DATA.HR_TIME:UPDATE"), TimecardOwnerRoute.QUERY))
                .isEmpty();
        assertThat(source.verify(request(
                TimecardOwnerRoute.QUERY, PopulationKind.TARGET_POPULATION,
                "APP.HCM:VIEW,DATA.HR_TIME:VIEW"), TimecardOwnerRoute.QUERY))
                .isEmpty();
        assertThat(source.verify(request(
                TimecardOwnerRoute.QUERY, PopulationKind.TARGET_POPULATION,
                "APP.HCM:VIEW,DATA.HR_TIME:APPROVE"), TimecardOwnerRoute.QUERY))
                .isPresent();
    }

    @Test
    void selfCorrectionCannotExpandIntoTargetAndTargetCorrectionRequiresUpdate() {
        assertThat(source.verify(request(
                TimecardOwnerRoute.CORRECT, PopulationKind.SELF,
                "APP.HCM:VIEW,DATA.HR_TIME:CREATE"), TimecardOwnerRoute.CORRECT))
                .isPresent();
        assertThat(source.verify(request(
                TimecardOwnerRoute.CORRECT, PopulationKind.TARGET_POPULATION,
                "APP.HCM:VIEW,DATA.HR_TIME:CREATE"), TimecardOwnerRoute.CORRECT))
                .isEmpty();
        assertThat(source.verify(request(
                TimecardOwnerRoute.CORRECT, PopulationKind.TARGET_POPULATION,
                "APP.HCM:VIEW,DATA.HR_TIME:UPDATE"), TimecardOwnerRoute.CORRECT))
                .isPresent();
    }

    @Test
    void submitAndDecisionRequireExactPopulationPermissionPairs() {
        assertThat(source.verify(request(
                TimecardOwnerRoute.SUBMIT, PopulationKind.SELF,
                "APP.HCM:VIEW,DATA.HR_TIME:CREATE"), TimecardOwnerRoute.SUBMIT))
                .isPresent();
        assertThat(source.verify(request(
                TimecardOwnerRoute.DECIDE, PopulationKind.TARGET_POPULATION,
                "APP.HCM:VIEW,DATA.HR_TIME:APPROVE"), TimecardOwnerRoute.DECIDE))
                .isPresent();
        unavailable(() -> source.verify(request(
                TimecardOwnerRoute.DECIDE, PopulationKind.SELF,
                "APP.HCM:VIEW,DATA.HR_TIME:APPROVE"), TimecardOwnerRoute.DECIDE));
    }

    @Test
    void currentDecisionPurposeAndCompleteFieldProjectionAreMandatory() {
        MockHttpServletRequest stale = request(
                TimecardOwnerRoute.CORRECT, PopulationKind.SELF,
                "APP.HCM:VIEW,DATA.HR_TIME:CREATE");
        stale.removeHeader(GatewayVerifiedTimecardOwnerAuthoritySource.EXPECTED_REVISION_HEADER);
        stale.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.EXPECTED_REVISION_HEADER,
                "psr-" + "f".repeat(64));
        assertThatThrownBy(() -> source.verify(stale, TimecardOwnerRoute.CORRECT))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.DECISION_REVISION_CONFLICT));

        MockHttpServletRequest wrongPurpose = request(
                TimecardOwnerRoute.CORRECT, PopulationKind.SELF,
                "APP.HCM:VIEW,DATA.HR_TIME:CREATE");
        wrongPurpose.removeHeader(GatewayVerifiedTimecardOwnerAuthoritySource.PURPOSE_HEADER);
        wrongPurpose.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.PURPOSE_HEADER,
                "COMMAND_STATUS");
        unavailable(() -> source.verify(wrongPurpose, TimecardOwnerRoute.CORRECT));

        MockHttpServletRequest masked = request(
                TimecardOwnerRoute.QUERY, PopulationKind.SELF,
                "APP.HCM:VIEW,DATA.HR_TIME:VIEW");
        masked.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.MASKED_FIELD_GROUPS_HEADER,
                "TIME_DETAIL");
        unavailable(() -> source.verify(masked, TimecardOwnerRoute.QUERY));
    }

    @Test
    void receiptRouteKindDoesNotChangeTheOriginatingPopulationDigest() {
        var originating = source.verify(request(
                TimecardOwnerRoute.CORRECT, PopulationKind.SELF,
                "APP.HCM:VIEW,DATA.HR_TIME:CREATE"), TimecardOwnerRoute.CORRECT)
                .orElseThrow();
        var recovery = source.verify(request(
                TimecardOwnerRoute.RECEIPT, PopulationKind.TARGET_POPULATION,
                "APP.HCM:VIEW,DATA.HRIS_COMMAND_RECEIPT:VIEW,DATA.HR_TIME:CREATE"),
                TimecardOwnerRoute.RECEIPT).orElseThrow();

        assertThat(recovery.populationScopeDigest())
                .isEqualTo(originating.populationScopeDigest());
        assertThat(recovery.populationKind()).isEqualTo(PopulationKind.TARGET_POPULATION);
    }

    @Test
    void selfScopeRequiresCurrentActorToWorkerIdentityEvidence() {
        MockHttpServletRequest request = request(
                TimecardOwnerRoute.SUBMIT, PopulationKind.SELF,
                "APP.HCM:VIEW,DATA.HR_TIME:CREATE");
        currentActorWorker = ACTOR_WORKER;

        unavailable(() -> source.verify(request, TimecardOwnerRoute.SUBMIT));
    }

    private MockHttpServletRequest request(
            TimecardOwnerRoute route, PopulationKind populationKind, String permissions) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        currentActorWorker = populationKind == PopulationKind.SELF ? WORKER : ACTOR_WORKER;
        request.setMethod(route.command() ? "POST" : "GET");
        request.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.SERVICE_TOKEN_HEADER,
                "time-service-token");
        request.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.TENANT_HEADER, "41");
        request.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.USER_HEADER, "72");
        request.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.PERMISSIONS_HEADER,
                permissions);
        request.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.ROUTE_HEADER,
                route.authorityContractKey());
        request.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.CONTEXT_HEADER, CONTEXT);
        activeScope = HcmEligibilityScopeKey.derived(
                41L, 72L, "hcm.timecard", route.bindingId(),
                "relationship-17", "population-17");
        request.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.SCOPE_HEADER, activeScope);
        request.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.ACCESS_MODE_HEADER,
                "NORMAL");
        request.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.CURRENT_REVISION_HEADER,
                DECISION);
        request.addHeader(
                GatewayVerifiedTimecardOwnerAuthoritySource.CURRENT_REVALIDATE_AT_HEADER,
                NOW.plusSeconds(60).toString());
        request.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.ROLLOUT_STATE_HEADER,
                "110");
        request.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.ROLLOUT_REVISION_HEADER,
                ROLLOUT);
        request.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.ROLLOUT_COHORT_HEADER,
                "full");
        request.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.CORRELATION_HEADER,
                CORRELATION.toString());
        request.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.SUBJECT_HEADER,
                SUBJECT.toString());
        request.addHeader(
                GatewayVerifiedTimecardOwnerAuthoritySource.FIELD_POLICY_REVISION_HEADER, "17");
        request.addHeader(
                GatewayVerifiedTimecardOwnerAuthoritySource.AUTHORIZATION_REVISION_HEADER, "23");
        request.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.POPULATION_KIND_HEADER,
                populationKind.name());
        request.addHeader(GatewayVerifiedTimecardOwnerAuthoritySource.PURPOSE_HEADER,
                route.requiredPurpose());
        if (route.command()) {
            request.addHeader(
                    GatewayVerifiedTimecardOwnerAuthoritySource.EXPECTED_REVISION_HEADER,
                    DECISION);
        }
        return request;
    }

    private PopulationProjection projection(String gatewayScopeKey) {
        assertThat(gatewayScopeKey).isEqualTo(activeScope);
        return new PopulationProjection(
                41, 72, gatewayScopeKey, POPULATION, 9, 11,
                "d".repeat(64), "e".repeat(64), NOW.plusSeconds(300),
                List.of(new Member(WORKER, ASSIGNMENT, 7, 13, "f".repeat(64))));
    }

    private void unavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
