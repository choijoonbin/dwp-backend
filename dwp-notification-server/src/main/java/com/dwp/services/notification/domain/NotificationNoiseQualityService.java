package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationNoiseQualityModels.FourEyesGovernance;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.FourEyesGovernanceRow;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseAggregateRow;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseQuality;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseQualityQuery;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseTimeRange;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseTrendPoint;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseTrendRow;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseTypeMetric;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationNoiseQualityService {

    static final int MAX_TYPES = 100;
    static final int FATIGUE_THRESHOLD_24_HOURS = 20;
    static final int FATIGUE_THRESHOLD_7_DAYS = 35;
    static final int FATIGUE_THRESHOLD_30_DAYS = 50;

    private final NotificationDatabaseScope databaseScope;
    private final NotificationNoiseQualityRepository repository;
    private final NotificationAttentionGovernanceRuntime governanceRuntime;
    private final Clock clock;

    @Autowired
    public NotificationNoiseQualityService(
            NotificationDatabaseScope databaseScope,
            NotificationNoiseQualityRepository repository,
            NotificationAttentionGovernanceRuntime governanceRuntime) {
        this(databaseScope, repository, governanceRuntime, Clock.systemUTC());
    }

    NotificationNoiseQualityService(
            NotificationDatabaseScope databaseScope,
            NotificationNoiseQualityRepository repository,
            NotificationAttentionGovernanceRuntime governanceRuntime,
            Clock clock) {
        this.databaseScope = databaseScope;
        this.repository = repository;
        this.governanceRuntime = governanceRuntime;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NoiseQuality summary(NotificationRequestContext.Actor actor) {
        return summary(actor, NoiseQualityQuery.defaults());
    }

    @Transactional(readOnly = true)
    public NoiseQuality summary(
            NotificationRequestContext.Actor actor,
            NoiseQualityQuery query) {
        databaseScope.applyWorker(actor.tenantId());
        Instant generatedAt = Instant.now(clock);
        Instant windowStart = query.range().windowStart(generatedAt);
        List<String> unavailableSources = new ArrayList<>();
        FourEyesGovernance fourEyes = governance(actor.tenantId(), unavailableSources);
        int minimumCohortSize = governanceRuntime.minimumAnalyticsCohort(actor.tenantId());
        long observed = repository.observedCohortSize(
                actor.tenantId(), windowStart, query.search());
        if (observed < minimumCohortSize) {
            return response(
                    unavailableSources,
                    false,
                    minimumCohortSize,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    fourEyes,
                    query.range(),
                    windowStart,
                    generatedAt);
        }

        List<NoiseAggregateRow> fetched = repository.aggregates(
                actor.tenantId(),
                windowStart,
                minimumCohortSize,
                MAX_TYPES + 1,
                query.search(),
                query.severity(),
                query.risk());
        boolean truncated = fetched.size() > MAX_TYPES;
        if (truncated) unavailableSources.add("NOISE_TYPE_LIMIT_REACHED");
        List<NoiseAggregateRow> rows = truncated
                ? List.copyOf(fetched.subList(0, MAX_TYPES))
                : List.copyOf(fetched);
        List<UUID> contractIds = rows.stream().map(NoiseAggregateRow::contractId).distinct().toList();
        Long fatigueExposed = null;
        List<NoiseTrendPoint> trend = List.of();
        if (!contractIds.isEmpty()) {
            fatigueExposed = privacyProtectedCount(repository.fatigueExposedUsers(
                    actor.tenantId(),
                    windowStart,
                    fatigueThreshold(query.range()),
                    contractIds), minimumCohortSize);
            trend = repository.trend(
                            actor.tenantId(),
                            windowStart,
                            query.range().bucket(),
                            minimumCohortSize,
                            contractIds)
                    .stream()
                    .map(this::trendPoint)
                    .toList();
        }
        return response(
                unavailableSources,
                true,
                minimumCohortSize,
                observed,
                weightedRate(rows, NoiseAggregateRow::mutingRecipients,
                        NoiseAggregateRow::recipientCount),
                weightedRate(rows, NoiseAggregateRow::deduplicatedOccurrences,
                        NoiseAggregateRow::deduplicationEligibleOccurrences),
                weightedRate(rows, NoiseAggregateRow::completedActions,
                        NoiseAggregateRow::actionable),
                fatigueExposed,
                rows.stream().map(this::metric).toList(),
                trend,
                fourEyes,
                query.range(),
                windowStart,
                generatedAt);
    }

    private NoiseQuality response(
            List<String> unavailableSources,
            boolean sufficientCohort,
            int minimumCohortSize,
            Long observedCohortSize,
            Double muteRate,
            Double deduplicationRate,
            Double actionConversionRate,
            Long fatigueExposedUsers,
            List<NoiseTypeMetric> noisyTypes,
            List<NoiseTrendPoint> trend,
            FourEyesGovernance fourEyes,
            NoiseTimeRange range,
            Instant windowStart,
            Instant generatedAt) {
        boolean partial = !unavailableSources.isEmpty();
        return new NoiseQuality(
                partial,
                unavailableSources,
                partial ? "Some notification quality sources were unavailable or truncated." : null,
                sufficientCohort,
                minimumCohortSize,
                observedCohortSize,
                muteRate,
                deduplicationRate,
                actionConversionRate,
                fatigueExposedUsers,
                noisyTypes,
                trend,
                fourEyes,
                range,
                windowStart,
                generatedAt);
    }

    private FourEyesGovernance governance(long tenantId, List<String> unavailableSources) {
        try {
            FourEyesGovernanceRow row = repository.fourEyesGovernance(tenantId);
            if (row == null) {
                unavailableSources.add("POLICY_GOVERNANCE");
                return unavailableGovernance();
            }
            if (row.publishedPolicyCount() == 0) {
                return new FourEyesGovernance(
                        "NOT_CONFIGURED",
                        0,
                        row.draftPolicyCount(),
                        false,
                        row.updatedAt());
            }
            if (!row.publishedEvidenceValid()) {
                unavailableSources.add("POLICY_GOVERNANCE_EVIDENCE");
                return new FourEyesGovernance(
                        "UNAVAILABLE",
                        row.publishedPolicyCount(),
                        row.draftPolicyCount(),
                        false,
                        row.updatedAt());
            }
            return new FourEyesGovernance(
                    "ENFORCED",
                    row.publishedPolicyCount(),
                    row.draftPolicyCount(),
                    true,
                    row.updatedAt());
        } catch (DataAccessException exception) {
            unavailableSources.add("POLICY_GOVERNANCE");
            return unavailableGovernance();
        }
    }

    private FourEyesGovernance unavailableGovernance() {
        return new FourEyesGovernance("UNAVAILABLE", 0, 0, false, null);
    }

    private int fatigueThreshold(NoiseTimeRange range) {
        return switch (range) {
            case LAST_24_HOURS -> FATIGUE_THRESHOLD_24_HOURS;
            case LAST_7_DAYS -> FATIGUE_THRESHOLD_7_DAYS;
            case LAST_30_DAYS -> FATIGUE_THRESHOLD_30_DAYS;
        };
    }

    private NoiseTypeMetric metric(NoiseAggregateRow row) {
        return new NoiseTypeMetric(
                row.contractId(),
                row.appKey(),
                row.typeKey(),
                row.ownerTeam(),
                row.recipientCount(),
                row.volume(),
                rate(row.mutingRecipients(), row.recipientCount()),
                rate(row.deduplicatedOccurrences(),
                        row.deduplicationEligibleOccurrences()),
                rate(row.completedActions(), row.actionable()),
                row.findingCode(),
                row.findingSeverity(),
                row.findingTarget(),
                row.findingTargetKey());
    }

    private NoiseTrendPoint trendPoint(NoiseTrendRow row) {
        return new NoiseTrendPoint(
                row.bucketStart(),
                row.recipientCount(),
                row.volume(),
                rate(row.mutingRecipients(), row.recipientCount()),
                rate(row.deduplicatedOccurrences(),
                        row.deduplicationEligibleOccurrences()),
                rate(row.completedActions(), row.actionable()));
    }

    private Long privacyProtectedCount(long count, int minimumCohortSize) {
        if (count == 0) return 0L;
        return count >= minimumCohortSize ? count : null;
    }

    private Double weightedRate(
            List<NoiseAggregateRow> rows,
            LongValue numerator,
            LongValue denominator) {
        long totalNumerator = rows.stream().mapToLong(numerator::value).sum();
        long totalDenominator = rows.stream().mapToLong(denominator::value).sum();
        return rate(totalNumerator, totalDenominator);
    }

    private Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    @FunctionalInterface
    private interface LongValue {
        long value(NoiseAggregateRow row);
    }
}
