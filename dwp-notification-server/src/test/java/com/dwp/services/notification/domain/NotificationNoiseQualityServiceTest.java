package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.FourEyesGovernanceRow;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseAggregateRow;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseFindingSeverity;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseQuality;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseQualityQuery;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseRisk;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseTimeRange;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseTrendRow;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationNoiseQualityServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final Instant WINDOW_START = Instant.parse("2026-08-17T00:00:00Z");
    private static final UUID CONTRACT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(
                    42L, 17L, Set.of(), Set.of(), false, "dwp-gateway");

    private final NotificationDatabaseScope databaseScope = mock(NotificationDatabaseScope.class);
    private final NotificationNoiseQualityRepository repository =
            mock(NotificationNoiseQualityRepository.class);
    private final NotificationAttentionGovernanceRuntime governanceRuntime =
            mock(NotificationAttentionGovernanceRuntime.class);
    private final NotificationNoiseQualityService service = new NotificationNoiseQualityService(
            databaseScope, repository, governanceRuntime, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void governanceEvidence() {
        when(governanceRuntime.minimumAnalyticsCohort(42L)).thenReturn(10);
        when(repository.fourEyesGovernance(42L)).thenReturn(new FourEyesGovernanceRow(
                2, 1, true, Instant.parse("2026-09-15T00:00:00Z")));
    }

    @Test
    void suppressesEveryMetricBelowThePrivacyThreshold() {
        when(repository.observedCohortSize(42L, WINDOW_START, null)).thenReturn(9L);

        NoiseQuality result = service.summary(ACTOR);

        assertThat(result.sufficientCohort()).isFalse();
        assertThat(result.minimumCohortSize()).isEqualTo(10);
        assertThat(result.observedCohortSize()).isNull();
        assertThat(result.noisyTypes()).isEmpty();
        assertThat(result.trend()).isEmpty();
        assertThat(result.fourEyes().state()).isEqualTo("ENFORCED");
        verify(repository, never()).aggregates(42L, WINDOW_START, 10, 101, null, null, null);
    }

    @Test
    void derivesRatesOwnerTargetsAndTrendOnlyFromPrivacyEligibleRows() {
        NoiseAggregateRow row = row(
                10, 20, 2, 5, 8, 4,
                null, null, "CONTRACT", CONTRACT_ID.toString());
        when(repository.observedCohortSize(42L, WINDOW_START, null)).thenReturn(12L);
        when(repository.aggregates(42L, WINDOW_START, 10, 101, null, null, null))
                .thenReturn(List.of(row));
        when(repository.fatigueExposedUsers(42L, WINDOW_START, 50, List.of(CONTRACT_ID)))
                .thenReturn(0L);
        when(repository.trend(42L, WINDOW_START, "day", 10, List.of(CONTRACT_ID)))
                .thenReturn(List.of(new NoiseTrendRow(
                        Instant.parse("2026-09-15T00:00:00Z"),
                        10, 20, 2, 20, 5, 8, 4)));

        NoiseQuality result = service.summary(ACTOR);

        assertThat(result.muteRate()).isEqualTo(0.2);
        assertThat(result.deduplicationRate()).isEqualTo(0.25);
        assertThat(result.actionConversionRate()).isEqualTo(0.5);
        assertThat(result.fatigueExposedUsers()).isZero();
        assertThat(result.noisyTypes()).singleElement().satisfies(metric -> {
            assertThat(metric.contractId()).isEqualTo(CONTRACT_ID);
            assertThat(metric.ownerTeam()).isEqualTo("Approval Platform");
            assertThat(metric.findingTargetKey()).isEqualTo(CONTRACT_ID.toString());
        });
        assertThat(result.trend()).singleElement().satisfies(point -> {
            assertThat(point.cohortSize()).isEqualTo(10);
            assertThat(point.muteRate()).isEqualTo(0.2);
        });
        verify(databaseScope).applyWorker(42L);
    }

    @Test
    void forwardsBoundedRangeSearchSeverityAndRiskToTheRepository() {
        Instant dayStart = Instant.parse("2026-09-15T00:00:00Z");
        NoiseQualityQuery query = new NoiseQualityQuery(
                NoiseTimeRange.LAST_24_HOURS,
                "  approvals  ",
                NoiseFindingSeverity.WARNING,
                NoiseRisk.LOW_ACTION_CONVERSION);
        when(repository.observedCohortSize(42L, dayStart, "approvals")).thenReturn(20L);
        when(repository.aggregates(
                42L, dayStart, 10, 101, "approvals",
                NoiseFindingSeverity.WARNING, NoiseRisk.LOW_ACTION_CONVERSION))
                .thenReturn(List.of());

        NoiseQuality result = service.summary(ACTOR, query);

        assertThat(result.range()).isEqualTo(NoiseTimeRange.LAST_24_HOURS);
        assertThat(result.windowStart()).isEqualTo(dayStart);
        assertThat(result.noisyTypes()).isEmpty();
    }

    @Test
    void appliesThePublishedTenantAnalyticsCohortToEveryPrivacyBoundary() {
        when(governanceRuntime.minimumAnalyticsCohort(42L)).thenReturn(25);
        when(repository.observedCohortSize(42L, WINDOW_START, null)).thenReturn(30L);
        when(repository.aggregates(42L, WINDOW_START, 25, 101, null, null, null))
                .thenReturn(List.of(row(
                        25, 50, 5, 10, 20, 10,
                        null, null, "CONTRACT", CONTRACT_ID.toString())));
        when(repository.fatigueExposedUsers(42L, WINDOW_START, 50, List.of(CONTRACT_ID)))
                .thenReturn(20L);
        when(repository.trend(42L, WINDOW_START, "day", 25, List.of(CONTRACT_ID)))
                .thenReturn(List.of());

        NoiseQuality result = service.summary(ACTOR);

        assertThat(result.minimumCohortSize()).isEqualTo(25);
        assertThat(result.sufficientCohort()).isTrue();
        assertThat(result.fatigueExposedUsers()).isNull();
        verify(repository).aggregates(42L, WINDOW_START, 25, 101, null, null, null);
        verify(repository).trend(42L, WINDOW_START, "day", 25, List.of(CONTRACT_ID));
    }

    @Test
    void alsoAppliesTheRuntimeFallbackCohortWhenNoRevisionIsPublished() {
        when(governanceRuntime.minimumAnalyticsCohort(42L)).thenReturn(30);
        when(repository.observedCohortSize(42L, WINDOW_START, null)).thenReturn(29L);

        NoiseQuality result = service.summary(ACTOR);

        assertThat(result.minimumCohortSize()).isEqualTo(30);
        assertThat(result.sufficientCohort()).isFalse();
        verify(repository, never()).aggregates(42L, WINDOW_START, 30, 101, null, null, null);
    }

    @Test
    void failsClosedBeforeReadingAnalyticsWhenGovernanceResolutionFails() {
        when(governanceRuntime.minimumAnalyticsCohort(42L)).thenThrow(
                new NotificationException(
                        NotificationErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));

        assertThatThrownBy(() -> service.summary(ACTOR))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(NotificationErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        verify(repository, never()).observedCohortSize(42L, WINDOW_START, null);
    }

    @Test
    void withholdsASmallFatigueCountEvenInsideAValidOverallCohort() {
        NoiseAggregateRow row = row(
                20, 100, 6, 4, 20, 3,
                "HIGH_MUTE_RATE", "WARNING", "POLICY",
                "20000000-0000-0000-0000-000000000001");
        when(repository.observedCohortSize(42L, WINDOW_START, null)).thenReturn(30L);
        when(repository.aggregates(42L, WINDOW_START, 10, 101, null, null, null))
                .thenReturn(List.of(row));
        when(repository.fatigueExposedUsers(42L, WINDOW_START, 50, List.of(CONTRACT_ID)))
                .thenReturn(3L);
        when(repository.trend(42L, WINDOW_START, "day", 10, List.of(CONTRACT_ID)))
                .thenReturn(List.of());

        NoiseQuality result = service.summary(ACTOR);

        assertThat(result.fatigueExposedUsers()).isNull();
        assertThat(result.noisyTypes()).singleElement().satisfies(metric -> {
            assertThat(metric.findingCode()).isEqualTo("HIGH_MUTE_RATE");
            assertThat(metric.findingTarget()).isEqualTo("POLICY");
        });
    }

    @Test
    void neverClaimsFourEyesEnforcementWithoutPublishedEvidence() {
        when(repository.observedCohortSize(42L, WINDOW_START, null)).thenReturn(0L);
        when(repository.fourEyesGovernance(42L)).thenReturn(new FourEyesGovernanceRow(
                0, 2, false, Instant.parse("2026-09-15T00:00:00Z")));
        assertThat(service.summary(ACTOR).fourEyes()).satisfies(policy -> {
            assertThat(policy.state()).isEqualTo("NOT_CONFIGURED");
            assertThat(policy.reviewerSeparationRequired()).isFalse();
        });

        when(repository.fourEyesGovernance(42L)).thenReturn(new FourEyesGovernanceRow(
                1, 0, false, Instant.parse("2026-09-15T00:00:00Z")));
        assertThat(service.summary(ACTOR).fourEyes()).satisfies(policy -> {
            assertThat(policy.state()).isEqualTo("UNAVAILABLE");
            assertThat(policy.reviewerSeparationRequired()).isFalse();
        });
    }

    @Test
    void degradesGovernanceEvidenceFailureWithoutInventingAnEnforcedState() {
        when(repository.observedCohortSize(42L, WINDOW_START, null)).thenReturn(0L);
        when(repository.fourEyesGovernance(42L))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));

        NoiseQuality result = service.summary(ACTOR);

        assertThat(result.partial()).isTrue();
        assertThat(result.unavailableSources()).contains("POLICY_GOVERNANCE");
        assertThat(result.fourEyes().state()).isEqualTo("UNAVAILABLE");
    }

    private NoiseAggregateRow row(
            long recipients,
            long volume,
            long mutingRecipients,
            long deduplicatedOccurrences,
            long actionable,
            long completedActions,
            String findingCode,
            String severity,
            String target,
            String targetKey) {
        return new NoiseAggregateRow(
                CONTRACT_ID,
                "approvals",
                "APPROVAL.ACTION_REQUIRED",
                "Approval Platform",
                recipients,
                volume,
                mutingRecipients,
                volume,
                deduplicatedOccurrences,
                actionable,
                completedActions,
                findingCode,
                severity,
                target,
                targetKey);
    }
}
