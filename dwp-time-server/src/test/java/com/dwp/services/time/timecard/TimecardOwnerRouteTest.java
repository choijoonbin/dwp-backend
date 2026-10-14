package com.dwp.services.time.timecard;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.services.time.timecard.TimecardModels.PopulationKind;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TimecardOwnerRouteTest {

    private static final String APP = "APP.HCM:VIEW";
    private static final String VIEW = "DATA.HR_TIME:VIEW";
    private static final String CREATE = "DATA.HR_TIME:CREATE";
    private static final String UPDATE = "DATA.HR_TIME:UPDATE";
    private static final String APPROVE = "DATA.HR_TIME:APPROVE";

    @Test
    void populationAndResolvedPermissionAreOneFailClosedProfile() {
        assertThat(TimecardOwnerRoute.QUERY.accepts(
                PopulationKind.SELF, Set.of(APP, VIEW))).isTrue();
        assertThat(TimecardOwnerRoute.QUERY.accepts(
                PopulationKind.SELF, Set.of(APP, UPDATE))).isFalse();
        assertThat(TimecardOwnerRoute.QUERY.accepts(
                PopulationKind.TARGET_POPULATION, Set.of(APP, VIEW))).isFalse();
        assertThat(TimecardOwnerRoute.QUERY.accepts(
                PopulationKind.TARGET_POPULATION, Set.of(APP, UPDATE))).isTrue();
        assertThat(TimecardOwnerRoute.QUERY.accepts(
                PopulationKind.TARGET_POPULATION, Set.of(APP, APPROVE))).isTrue();

        assertThat(TimecardOwnerRoute.CORRECT.accepts(
                PopulationKind.SELF, Set.of(APP, CREATE))).isTrue();
        assertThat(TimecardOwnerRoute.CORRECT.accepts(
                PopulationKind.SELF, Set.of(APP, UPDATE))).isFalse();
        assertThat(TimecardOwnerRoute.CORRECT.accepts(
                PopulationKind.TARGET_POPULATION, Set.of(APP, CREATE))).isFalse();
        assertThat(TimecardOwnerRoute.CORRECT.accepts(
                PopulationKind.TARGET_POPULATION, Set.of(APP, UPDATE))).isTrue();

        assertThat(TimecardOwnerRoute.SUBMIT.accepts(
                PopulationKind.SELF, Set.of(APP, CREATE))).isTrue();
        assertThat(TimecardOwnerRoute.SUBMIT.accepts(
                PopulationKind.TARGET_POPULATION, Set.of(APP, CREATE))).isFalse();
        assertThat(TimecardOwnerRoute.DECIDE.accepts(
                PopulationKind.TARGET_POPULATION, Set.of(APP, APPROVE))).isTrue();
        assertThat(TimecardOwnerRoute.DECIDE.accepts(
                PopulationKind.SELF, Set.of(APP, APPROVE))).isFalse();
    }

    @Test
    void pinsFiveExactRoutesAndControlChecksums() {
        assertThat(TimecardOwnerRoute.values()).hasSize(5);
        assertThat(TimecardOwnerRoute.AUTHORIZATION_VERSION).isEqualTo(42);
        assertThat(TimecardOwnerRoute.AUTHORIZATION_SHA256)
                .isEqualTo("f73f9a64816ac8f5567a2a7c292821f4a40e6d675915ebc3c3ac8aad7d1fd983");
        assertThat(TimecardOwnerRoute.GATEWAY_OPENAPI_SHA256)
                .isEqualTo("50a57ad5aa93178f47d724ca3dfa08a64985de93a099071db66220dfe686c2a2");
    }
}
