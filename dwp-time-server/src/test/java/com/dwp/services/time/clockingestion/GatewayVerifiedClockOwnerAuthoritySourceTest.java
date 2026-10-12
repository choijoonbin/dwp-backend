package com.dwp.services.time.clockingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockAction;
import com.dwp.services.time.clockingestion.ClockOwnerPolicyProjectionResolver.Member;
import com.dwp.services.time.clockingestion.ClockOwnerPolicyProjectionResolver.PopulationProjection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class GatewayVerifiedClockOwnerAuthoritySourceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T02:00:00Z");
    private static final UUID SUBJECT = UUID.fromString("71000000-0000-4000-8000-000000000001");
    private static final UUID POPULATION = UUID.fromString("71000000-0000-4000-8000-000000000002");
    private static final UUID WORKER = UUID.fromString("71000000-0000-4000-8000-000000000003");
    private static final UUID ASSIGNMENT = UUID.fromString("71000000-0000-4000-8000-000000000004");
    private static final UUID OTHER_WORKER = UUID.fromString("71000000-0000-4000-8000-000000000005");
    private static final UUID OTHER_ASSIGNMENT = UUID.fromString("71000000-0000-4000-8000-000000000006");
    private static final UUID SOURCE = UUID.fromString("71000000-0000-4000-8000-000000000007");
    private static final String DECISION = "psr-" + "a".repeat(64);
    private static final String CONTEXT = "psc-" + "b".repeat(64);
    private static final String ROLLOUT = "rollout-" + "c".repeat(64);
    private static final String CORRELATION = "71000000-0000-4000-8000-000000000008";

    private final ClockOwnerPolicyProjectionResolver resolver =
            (tenant, actor, scope, checkedAt) -> Optional.of(projection(scope));
    private final GatewayVerifiedClockOwnerAuthoritySource source =
            new GatewayVerifiedClockOwnerAuthoritySource(
                    "time-service-token", true, Clock.fixed(NOW, ZoneOffset.UTC), resolver);

    @Test
    void pageDecisionProjectsBoundedPopulationFieldPolicyAndCorrectEntitlement() {
        MockHttpServletRequest request = request(ClockOwnerRoute.WORKBENCH, "NORMAL");
        request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.MASKED_FIELD_GROUPS_HEADER,
                "QUALITY_FINDING");
        request.addHeader("X-DWP-Roles", "TENANT_DEFINED_TIME_ADMIN");

        var verified = source.verify(request, ClockOwnerRoute.WORKBENCH).orElseThrow();

        assertThat(verified.actions()).containsExactlyInAnyOrder(
                ClockAction.VIEW, ClockAction.CORRECT);
        assertThat(verified.authorizedWorkerPublicIds())
                .containsExactlyInAnyOrder(WORKER, OTHER_WORKER);
        assertThat(verified.maskedFieldGroups()).containsExactly("QUALITY_FINDING");
        assertThat(verified.omittedFieldGroups()).isEmpty();
        assertThat(verified.fieldPolicyRevision()).isEqualTo(17);
        assertThat(verified.authorizationRevision()).isEqualTo(23);
        assertThat(verified.decisionRevision()).isEqualTo(DECISION);
        assertThat(verified.purposeCode()).isEqualTo(ClockIngestionService.VIEW_PURPOSE);
        assertThat(verified.stepUpSatisfied()).isFalse();
        assertThat(verified.populationScopeDigest()).matches("[0-9a-f]{64}");
        assertThat(verified.queryScopeRevision()).matches("scope-[0-9a-f]{64}");
    }

    @Test
    void pageDecisionDoesNotOfferCorrectionWithoutTheExactUpdateCapability() {
        MockHttpServletRequest request = request(ClockOwnerRoute.WORKBENCH, "NORMAL");
        request.removeHeader(GatewayVerifiedClockOwnerAuthoritySource.PERMISSIONS_HEADER);
        request.addHeader(
                GatewayVerifiedClockOwnerAuthoritySource.PERMISSIONS_HEADER,
                "APP.HCM:VIEW,DATA.HR_TIME:VIEW");

        var verified = source.verify(request, ClockOwnerRoute.WORKBENCH).orElseThrow();

        assertThat(verified.actions()).containsExactly(ClockAction.VIEW);
    }

    @Test
    void recordDecisionProjectsExactTargetAndClockSourcePredicate() {
        MockHttpServletRequest request = request(ClockOwnerRoute.RECORD, "NORMAL");

        var verified = source.verify(request, ClockOwnerRoute.RECORD).orElseThrow();

        assertThat(verified.actions()).containsExactly(ClockAction.RECORD);
        assertThat(verified.workerPublicId()).isEqualTo(WORKER);
        assertThat(verified.peopleAssignmentPublicId()).isEqualTo(ASSIGNMENT);
        assertThat(verified.peopleAssignmentRevision()).isEqualTo(7);
        assertThat(verified.sourceScope()).containsExactly(SOURCE);
        assertThat(verified.purposeCode()).isEqualTo(ClockIngestionService.CAPTURE_PURPOSE);
    }

    @Test
    void voidDecisionRequiresExactCurrentRevisionAndElevatedModeForStepUp() {
        var normal = source.verify(
                request(ClockOwnerRoute.VOID, "NORMAL"), ClockOwnerRoute.VOID).orElseThrow();
        var elevated = source.verify(
                request(ClockOwnerRoute.VOID, "ELEVATED"), ClockOwnerRoute.VOID).orElseThrow();
        assertThat(normal.stepUpSatisfied()).isFalse();
        assertThat(elevated.stepUpSatisfied()).isTrue();

        MockHttpServletRequest mismatch = request(ClockOwnerRoute.VOID, "ELEVATED");
        mismatch.removeHeader(GatewayVerifiedClockOwnerAuthoritySource.EXPECTED_REVISION_HEADER);
        mismatch.addHeader(GatewayVerifiedClockOwnerAuthoritySource.EXPECTED_REVISION_HEADER,
                "psr-" + "f".repeat(64));
        assertThatThrownBy(() -> source.verify(mismatch, ClockOwnerRoute.VOID))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.DECISION_REVISION_CONFLICT));
    }

    @Test
    void missingTrustedPredicateOrMismatchedRouteFailsClosedWithoutRoleFallback() {
        MockHttpServletRequest missingPrincipal = request(ClockOwnerRoute.RECORD, "NORMAL");
        missingPrincipal.removeHeader(GatewayVerifiedClockOwnerAuthoritySource.SUBJECT_HEADER);
        unavailable(() -> source.verify(missingPrincipal, ClockOwnerRoute.RECORD));

        MockHttpServletRequest wrongRoute = request(ClockOwnerRoute.WORKBENCH, "NORMAL");
        wrongRoute.removeHeader(GatewayVerifiedClockOwnerAuthoritySource.ROUTE_HEADER);
        wrongRoute.addHeader(GatewayVerifiedClockOwnerAuthoritySource.ROUTE_HEADER,
                ClockOwnerRoute.TIME_EXCEPTIONS.authorityContractKey());
        unavailable(() -> source.verify(wrongRoute, ClockOwnerRoute.WORKBENCH));

        MockHttpServletRequest missingCapability = request(ClockOwnerRoute.RECORD, "NORMAL");
        missingCapability.removeHeader(GatewayVerifiedClockOwnerAuthoritySource.PERMISSIONS_HEADER);
        missingCapability.addHeader(GatewayVerifiedClockOwnerAuthoritySource.PERMISSIONS_HEADER,
                "APP.HCM:VIEW");
        missingCapability.addHeader("X-DWP-Roles", "HRIS_DUTY_TIME_OPERATOR");
        assertThat(source.verify(missingCapability, ClockOwnerRoute.RECORD)).isEmpty();
    }

    @Test
    void unknownCommandTargetAndMalformedFieldProjectionFailClosed() {
        MockHttpServletRequest target = request(ClockOwnerRoute.RECORD, "NORMAL");
        target.removeHeader(GatewayVerifiedClockOwnerAuthoritySource.TARGET_WORKER_HEADER);
        target.addHeader(GatewayVerifiedClockOwnerAuthoritySource.TARGET_WORKER_HEADER,
                UUID.randomUUID().toString());
        assertThat(source.verify(target, ClockOwnerRoute.RECORD)).isEmpty();

        MockHttpServletRequest field = request(ClockOwnerRoute.TIME_EXCEPTIONS, "NORMAL");
        field.addHeader(GatewayVerifiedClockOwnerAuthoritySource.OMITTED_FIELD_GROUPS_HEADER,
                "EXCEPTION,OWNER_DEEP_LINK,RECOVERY_METADATA");
        unavailable(() -> source.verify(field, ClockOwnerRoute.TIME_EXCEPTIONS));
    }

    private MockHttpServletRequest request(ClockOwnerRoute route, String accessMode) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.SERVICE_TOKEN_HEADER,
                "time-service-token");
        request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.TENANT_HEADER, "41");
        request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.USER_HEADER, "72");
        String capabilities = "APP.HCM:VIEW," + route.capability();
        if (route.action() == ClockAction.VIEW) capabilities += ",DATA.HR_TIME:UPDATE";
        request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.PERMISSIONS_HEADER,
                capabilities);
        request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.ROUTE_HEADER,
                route.authorityContractKey());
        request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.CONTEXT_HEADER, CONTEXT);
        request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.SCOPE_HEADER, scope(route));
        request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.ACCESS_MODE_HEADER, accessMode);
        request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.CURRENT_REVISION_HEADER,
                DECISION);
        request.addHeader(
                GatewayVerifiedClockOwnerAuthoritySource.CURRENT_REVALIDATE_AT_HEADER,
                NOW.plusSeconds(60).toString());
        request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.ROLLOUT_STATE_HEADER, "110");
        request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.ROLLOUT_REVISION_HEADER,
                ROLLOUT);
        request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.ROLLOUT_COHORT_HEADER, "full");
        request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.CORRELATION_HEADER,
                CORRELATION);
        request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.SUBJECT_HEADER,
                SUBJECT.toString());
        request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.FIELD_POLICY_REVISION_HEADER,
                "17");
        request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.AUTHORIZATION_REVISION_HEADER,
                "23");
        if (route.action() != ClockAction.VIEW) {
            request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.EXPECTED_REVISION_HEADER,
                    DECISION);
            request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.TARGET_WORKER_HEADER,
                    WORKER.toString());
            request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.TARGET_ASSIGNMENT_HEADER,
                    ASSIGNMENT.toString());
            request.addHeader(
                    GatewayVerifiedClockOwnerAuthoritySource.TARGET_ASSIGNMENT_REVISION_HEADER,
                    "7");
        }
        if (route == ClockOwnerRoute.RECORD) {
            request.addHeader(GatewayVerifiedClockOwnerAuthoritySource.SOURCE_SCOPE_HEADER,
                    SOURCE.toString());
        }
        return request;
    }

    private String scope(ClockOwnerRoute route) {
        return HcmEligibilityScopeKey.derived(
                41L, 72L, "hcm.operations.clock-ingestion", route.bindingId(),
                "relationship-9", "population-9");
    }

    private PopulationProjection projection(String gatewayScope) {
        return new PopulationProjection(
                41L,
                72L,
                gatewayScope,
                POPULATION,
                9,
                11,
                "a".repeat(64),
                "b".repeat(64),
                NOW.plusSeconds(300),
                List.of(
                        new Member(WORKER, ASSIGNMENT, 7, 13, "c".repeat(64)),
                        new Member(OTHER_WORKER, OTHER_ASSIGNMENT, 5, 12, "d".repeat(64))));
    }

    private void unavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }
}
