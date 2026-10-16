package com.dwp.services.time.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.dwp.platform.contracts.hris.home.HrisHomeMaterializationDigestV1;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.OmittedField;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.OwnerPopulationScope;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.TimeLeaveHomeData;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.ViewedNumber;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.ViewedText;
import com.dwp.services.time.home.TimeHomePayloadReferenceCodec.InvalidPayloadReferenceException;
import com.dwp.services.time.home.TimeHomeProjectionRepository.Projection;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TimeHomeContributionServiceTest {

    @Test
    void preparesAndReMaterializesSafeSelfProjectionWithMissingLeaveFailure() {
        var authority = TimeHomeTestFixtures.selfAuthority();
        var service = service(Clock.fixed(TimeHomeTestFixtures.NOW, ZoneOffset.UTC),
                ignored -> TimeHomeTestFixtures.projection());

        var prepared = service.prepare(authority);
        var data = (TimeLeaveHomeData) prepared.data();

        assertThat(prepared.profile()).isEqualTo(
                HrisHomeContributionMaterializationV1.PROFILE);
        assertThat(prepared.purposeCode()).isEqualTo(
                HrisHomeContributionMaterializationV1.PURPOSE);
        assertThat(prepared.state())
                .isEqualTo(HrisHomeContributionMaterializationV1.State.DEGRADED);
        assertThat(data.clockState()).isEqualTo(new ViewedText(Decision.VIEW, "CLOCKED_IN"));
        assertThat(data.leaveBalanceMinutes()).isEqualTo(new OmittedField(Decision.OMIT));
        assertThat(data.openTimecardCount()).isEqualTo(new ViewedNumber(Decision.VIEW, 3));
        assertThat(data.pendingApprovalCount()).isEqualTo(new ViewedNumber(Decision.VIEW, 2));
        assertThat(prepared.partialFailures()).singleElement()
                .satisfies(failure -> {
                    assertThat(failure.code()).isEqualTo(
                            HrisHomeContributionMaterializationV1.FailureCode.OWNER_UNAVAILABLE);
                    assertThat(failure.retryable()).isFalse();
                });
        assertThat(HrisHomeMaterializationDigestV1.matches(
                prepared, prepared.payloadDigest())).isTrue();
        assertThat(service.materialize(prepared.payloadRef(), authority)).isEqualTo(prepared);
    }

    @Test
    void aggregateProjectionAlwaysOmitsClockAndLeaveWhileApplyingCountPolicy() {
        Map<String, Decision> policy = Map.of(
                "clockState", Decision.VIEW,
                "leaveBalanceMinutes", Decision.VIEW,
                "openTimecardCount", Decision.MASK,
                "pendingApprovalCount", Decision.VIEW);
        var authority = TimeHomeTestFixtures.authority(OwnerPopulationScope.TIME_GROUP, policy);
        var service = service(Clock.fixed(TimeHomeTestFixtures.NOW, ZoneOffset.UTC),
                ignored -> TimeHomeTestFixtures.projection());

        var result = service.prepare(authority);
        var data = (TimeLeaveHomeData) result.data();

        assertThat(data.clockState()).isEqualTo(new OmittedField(Decision.OMIT));
        assertThat(data.leaveBalanceMinutes()).isEqualTo(new OmittedField(Decision.OMIT));
        assertThat(data.openTimecardCount())
                .isInstanceOf(HrisHomeContributionMaterializationV1.MaskedField.class);
        assertThat(data.pendingApprovalCount()).isEqualTo(new ViewedNumber(Decision.VIEW, 2));
        assertThat(result.deepLink()).isEqualTo("/hr/operations/time");
    }

    @Test
    void materializesOnlyTheThreeSealedCapabilityScopePairs() {
        var service = service(Clock.fixed(TimeHomeTestFixtures.NOW, ZoneOffset.UTC),
                ignored -> TimeHomeTestFixtures.projection());

        for (OwnerPopulationScope scope : java.util.List.of(
                OwnerPopulationScope.SELF,
                OwnerPopulationScope.TEAM,
                OwnerPopulationScope.TIME_GROUP)) {
            var authority = TimeHomeTestFixtures.authority(
                    scope, TimeHomeTestFixtures.all(Decision.VIEW));
            var result = service.prepare(authority);

            assertThat(result.ownerPopulationScope()).isEqualTo(scope);
            assertThat(result.browserAudience()).isEqualTo(authority.browserAudience());
            assertThat(result.populationScopeDigest())
                    .isEqualTo(authority.populationScopeDigest());
        }

        var self = TimeHomeTestFixtures.selfAuthority();
        assertThatThrownBy(() -> new TimeHomeCurrentAuthorityPort.CurrentAuthority(
                self.tenantId(), self.subjectPrincipalPublicId(),
                "hcm.operations.time.update", OwnerPopulationScope.SELF,
                self.scopeRevision(), self.populationScopeDigest(),
                self.effectivePermissionRevision(), self.fieldPolicyRevision(),
                self.widgetKey(), self.browserAudience(), self.workerPublicIds(),
                self.fieldDecisions()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ownerFailureProducesUnavailableClosedProjectionWithoutInventedValues() {
        var service = service(Clock.fixed(TimeHomeTestFixtures.NOW, ZoneOffset.UTC),
                ignored -> { throw new IllegalStateException("owner unavailable"); });

        var result = service.prepare(TimeHomeTestFixtures.selfAuthority());
        var data = (TimeLeaveHomeData) result.data();

        assertThat(result.state())
                .isEqualTo(HrisHomeContributionMaterializationV1.State.UNAVAILABLE);
        assertThat(data.decisions().values()).containsOnly(Decision.OMIT);
        assertThat(result.partialFailures()).singleElement()
                .extracting(HrisHomeContributionMaterializationV1.PartialFailure::retryable)
                .isEqualTo(true);
    }

    @Test
    void staleCrossTenantAndChangedProjectionReferencesFailClosed() {
        AtomicReference<Projection> projection =
                new AtomicReference<>(TimeHomeTestFixtures.projection());
        var initial = service(Clock.fixed(TimeHomeTestFixtures.NOW, ZoneOffset.UTC),
                ignored -> projection.get());
        var authority = TimeHomeTestFixtures.selfAuthority();
        var prepared = initial.prepare(authority);

        projection.set(new Projection("CLOCKED_OUT", 4, 1, 18, TimeHomeTestFixtures.NOW));
        assertThatThrownBy(() -> initial.materialize(prepared.payloadRef(), authority))
                .isInstanceOf(InvalidPayloadReferenceException.class);

        var otherTenant = new com.dwp.services.time.home.TimeHomeCurrentAuthorityPort
                .CurrentAuthority(
                        8, authority.subjectPrincipalPublicId(), authority.capability(),
                        authority.ownerPopulationScope(), authority.scopeRevision(),
                        authority.populationScopeDigest(), authority.effectivePermissionRevision(),
                        authority.fieldPolicyRevision(), authority.widgetKey(),
                        authority.browserAudience(), authority.workerPublicIds(),
                        authority.fieldDecisions());
        assertThatThrownBy(() -> initial.materialize(prepared.payloadRef(), otherTenant))
                .isInstanceOf(InvalidPayloadReferenceException.class);

        var expired = service(Clock.fixed(
                        TimeHomeTestFixtures.NOW.plusSeconds(301), ZoneOffset.UTC),
                ignored -> TimeHomeTestFixtures.projection());
        assertThatThrownBy(() -> expired.materialize(prepared.payloadRef(), authority))
                .isInstanceOf(InvalidPayloadReferenceException.class);
    }

    @Test
    void capsCountsToTheCanonicalBoundWithoutLeakingExtraDetail() {
        var service = service(Clock.fixed(TimeHomeTestFixtures.NOW, ZoneOffset.UTC),
                ignored -> new Projection("NOT_CLOCKED_IN", 10_000, 2_000, 99,
                        TimeHomeTestFixtures.NOW));
        var data = (TimeLeaveHomeData) service.prepare(
                TimeHomeTestFixtures.selfAuthority()).data();

        assertThat(((ViewedNumber) data.openTimecardCount()).value()).isEqualTo(999);
        assertThat(((ViewedNumber) data.pendingApprovalCount()).value()).isEqualTo(999);
    }

    private static TimeHomeContributionService service(
            Clock clock, ProjectionFunction projection) {
        return TimeHomeTestFixtures.service(
                clock,
                (authority, cutoff) -> projection.apply(cutoff),
                TimeHomeTestFixtures.disabledPublisher());
    }

    @FunctionalInterface
    private interface ProjectionFunction {
        Projection apply(java.time.Instant cutoff);
    }
}
