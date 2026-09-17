package com.dwp.services.approval.analytics;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.dwp.services.approval.analytics.ApprovalAnalyticsModels.*;

@Service
public class ApprovalAnalyticsService {
    private static final int ABSOLUTE_MINIMUM_COHORT = 5;
    private static final int MAXIMUM_REPRESENTATIVES = 10;
    private static final Duration MAXIMUM_WINDOW = Duration.ofDays(366);

    private static final List<MetricDefinition> DEFINITIONS = List.of(
            new MetricDefinition("cycle.p50", "Cycle time p50",
                    "Median completed_at - submitted_at for valid completed requests.",
                    "seconds", List.of("in-flight requests", "invalid temporal order")),
            new MetricDefinition("cycle.p90", "Cycle time p90",
                    "90th percentile completed_at - submitted_at for valid completed requests.",
                    "seconds", List.of("in-flight requests", "invalid temporal order")),
            new MetricDefinition("stage.wait", "Stage wait",
                    "completed_at - started_at for a completed stage.",
                    "seconds", List.of("open stages", "stages without a start timestamp")),
            new MetricDefinition("sla.compliance", "SLA compliance",
                    "Eligible requests not completed after due_at divided by SLA-eligible requests.",
                    "percent", List.of("requests without due_at", "future due_at")),
            new MetricDefinition("rework", "Rework incidence",
                    "Requests with a rework or information-request event.",
                    "requests", List.of()),
            new MetricDefinition("delegation", "Delegation incidence",
                    "Requests with a delegation event or delegated event marker.",
                    "requests", List.of()),
            new MetricDefinition("escalation", "Escalation incidence",
                    "Requests with an escalation event.", "requests", List.of()),
            new MetricDefinition("route.conformance", "Route conformance",
                    "Requests without route-override or operator-reassignment events.",
                    "percent", List.of("requests lacking a projected route fact")));

    private final ApprovalAnalyticsRepository repository;
    private final Clock clock;

    @Autowired
    public ApprovalAnalyticsService(ApprovalAnalyticsRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ApprovalAnalyticsService(ApprovalAnalyticsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(Scope scope, Query requested) {
        require(scope, Capability.VIEW);
        Query query = validate(requested);
        Instant generatedAt = clock.instant();
        ApprovalAnalyticsRepository.CoverageRow rawCoverage = repository.coverage(scope, query);
        ApprovalAnalyticsRepository.MetricRow rawOverall =
                repository.overall(scope, query, generatedAt);
        Coverage coverage = coverage(rawCoverage);
        Metrics overall = metrics(rawOverall, query.minimumCohortSize(), null, null);
        List<Cohort> cohorts = repository.cohorts(scope, query, generatedAt).stream()
                .map(row -> new Cohort(
                        row.key(), metrics(row.metrics(), query.minimumCohortSize(), null, null)))
                .toList();
        List<StageWait> stages = repository.stageWaits(scope, query).stream()
                .map(row -> new StageWait(
                        row.stepKey(), row.sequence(),
                        stageMetrics(row, query.minimumCohortSize())))
                .toList();
        return new Dashboard(
                generatedAt, query, DEFINITIONS, coverage, overall, cohorts, stages);
    }

    @Transactional(readOnly = true)
    public List<Representative> representatives(
            Scope scope,
            Query requested,
            String cohortKey,
            int limit,
            RepresentativeAuthorizer authorizer) {
        require(scope, Capability.DRILL_DOWN);
        Query query = validate(requested);
        if (cohortKey == null || !cohortKey.matches("[0-9a-fA-F-]{36}")
                || limit < 1 || limit > MAXIMUM_REPRESENTATIVES
                || authorizer == null) {
            throw invalid("The representative drill-down request is invalid.");
        }
        if (repository.cohortSize(scope, query, cohortKey) < query.minimumCohortSize()) {
            throw forbidden("Small cohorts cannot be drilled into.");
        }
        return repository.representatives(scope, query, cohortKey, 50).stream()
                .filter(row -> authorizer.canView(
                        scope.tenantId(), scope.resourceSetKey(),
                        scope.actorUserId(), row.requestId()))
                .limit(limit)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Representative> representatives(
            Scope scope,
            Query requested,
            String cohortKey,
            int limit) {
        return representatives(
                scope, requested, cohortKey, limit,
                repository::canViewRepresentative);
    }

    public List<MetricDefinition> metricDefinitions() {
        return DEFINITIONS;
    }

    private Query validate(Query query) {
        if (query == null || query.from() == null || query.to() == null
                || !query.to().isAfter(query.from())
                || Duration.between(query.from(), query.to()).compareTo(MAXIMUM_WINDOW) > 0
                || query.to().isAfter(clock.instant().plusSeconds(60))
                || query.cohortDimension() == null
                || query.minimumCohortSize() < ABSOLUTE_MINIMUM_COHORT
                || query.minimumCohortSize() > 100) {
            throw invalid("The analytics query is invalid.");
        }
        return query;
    }

    private Coverage coverage(ApprovalAnalyticsRepository.CoverageRow row) {
        Map<String, Integer> excluded = new LinkedHashMap<>();
        excluded.put("MISSING_SUBMITTED_AT", row.missingSubmitted());
        excluded.put("IN_FLIGHT_FOR_CYCLE", row.inFlightCycle());
        excluded.put("INVALID_TEMPORAL_ORDER", row.invalidCycle());
        double percent = row.candidateCount() == 0 ? 100.0
                : row.includedCount() * 100.0 / row.candidateCount();
        return new Coverage(
                row.candidateCount(), row.includedCount(), percent,
                Map.copyOf(excluded), row.sourceThrough(), row.projectedAt());
    }

    private Metrics metrics(
            ApprovalAnalyticsRepository.MetricRow row,
            int minimum,
            Long stageP50,
            Long stageP90) {
        if (row.sampleCount() < minimum) {
            return suppressed();
        }
        double sla = row.slaEligible() == 0 ? 100.0
                : (row.slaEligible() - row.slaBreached()) * 100.0 / row.slaEligible();
        double route = row.sampleCount() == 0 ? 100.0
                : row.routeConformant() * 100.0 / row.sampleCount();
        return new Metrics(
                false, Integer.toString(row.sampleCount()), row.sampleCount(),
                row.cycleP50(), row.cycleP90(), stageP50, stageP90,
                row.slaEligible(), row.slaBreached(), sla,
                row.reworked(), row.delegated(), row.escalated(),
                row.routeConformant(), route);
    }

    private Metrics stageMetrics(
            ApprovalAnalyticsRepository.StageMetricRow row,
            int minimum) {
        if (row.sampleCount() < minimum) {
            return suppressed();
        }
        return new Metrics(
                false, Integer.toString(row.sampleCount()), row.sampleCount(),
                null, null, row.waitP50(), row.waitP90(),
                null, null, null, null, null, null, null, null);
    }

    private Metrics suppressed() {
        return new Metrics(
                true, "<" + ABSOLUTE_MINIMUM_COHORT, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private void require(Scope scope, Capability capability) {
        if (scope == null || !scope.has(capability)) {
            throw forbidden("The analytics capability is not available.");
        }
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }
}
