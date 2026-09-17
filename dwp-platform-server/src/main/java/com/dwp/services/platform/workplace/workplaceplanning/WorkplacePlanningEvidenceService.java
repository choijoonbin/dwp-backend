package com.dwp.services.platform.workplace.workplaceplanning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceplanning.WorkplacePlanningDtos.*;
import static com.dwp.services.platform.workplace.workplaceplanning.WorkplacePlanningRepository.*;

@Service
public class WorkplacePlanningEvidenceService {
    static final Duration SOURCE_MAX_AGE = Duration.ofHours(24);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);
    private static final BigDecimal FULL_COVERAGE = new BigDecimal("100");
    private static final Set<String> RESOURCE_TYPES = Set.of(
            "ROOM", "DESK", "LOCKER", "PARKING", "FOCUS_POD", "PHONE_BOOTH", "EQUIPMENT");

    private final WorkplacePlanningRepository repository;
    private final Clock clock;

    @Autowired
    public WorkplacePlanningEvidenceService(WorkplacePlanningRepository repository) {
        this(repository, Clock.systemUTC());
    }

    WorkplacePlanningEvidenceService(WorkplacePlanningRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Trusted adapter entry point; it is intentionally not exposed by an HTTP controller. */
    @Transactional
    public PlanningSourceStatus observeSource(SourceObservation observation) {
        requireTenant(observation.tenantId());
        validateTrustedScope(observation.tenantId(), observation.scope());
        validateSourceObservation(observation);
        if (!repository.appendSourceObservation(observation)) {
            throw conflict("The source observation identifier or sequence already exists.");
        }
        return sourceStatus(new SourceRow(observation.observationId(), observation.tenantId(),
                observation.series(), canonicalScope(observation.scope()), observation.availability(),
                observation.coveragePercent(), observation.sourceAt(), observation.receivedAt(),
                observation.evidenceReference().trim(), List.copyOf(observation.exclusions()),
                List.copyOf(observation.points()), observation.sequence()), now());
    }

    /** Trusted forecasting adapter entry point; it is intentionally not exposed by HTTP. */
    @Transactional
    public ForecastProjection observeForecast(ForecastObservation observation) {
        requireTenant(observation.tenantId());
        validateTrustedScope(observation.tenantId(), observation.scope());
        validateForecastObservation(observation);
        if (!repository.appendForecast(observation)) {
            throw conflict("The forecast observation identifier already exists.");
        }
        ForecastRow row = new ForecastRow(observation.forecastId(), observation.tenantId(),
                canonicalScope(observation.scope()), observation.state(),
                normalized(observation.calculationVersion()),
                normalized(observation.evidenceReference()),
                List.copyOf(observation.sourceObservationIds()), List.copyOf(observation.points()),
                observation.recommendationMetrics(), List.copyOf(observation.limitations()),
                observation.sourceAt(), observation.receivedAt());
        return projection(row, observation.state(), observation.points(),
                observation.recommendationMetrics(), observation.limitations(), now());
    }

    /** Trusted meter/model adapter entry point; it is intentionally not exposed by HTTP. */
    @Transactional
    public EmissionProjection observeEmission(EmissionObservation observation) {
        requireTenant(observation.tenantId());
        validateEmissionObservation(observation);
        PlanningScope evidenceScope = new PlanningScope(observation.siteId(), observation.floorId(),
                null, null, observation.sourceAt(), observation.receivedAt().plusNanos(1));
        validateTrustedScope(observation.tenantId(), evidenceScope);
        if (!repository.appendEmissionEvidence(observation)) {
            throw conflict("The emission-evidence identifier already exists.");
        }
        return repository.emission(observation.tenantId(), observation.emissionEvidenceId())
                .orElseThrow(() -> conflict("The stored emission evidence could not be resolved."));
    }

    List<PlanningSourceStatus> sourceStatuses(long tenantId, PlanningScope scope) {
        Map<PlanningSeries, SourceRow> rows = new EnumMap<>(PlanningSeries.class);
        for (SourceRow row : repository.latestSources(tenantId, scope)) rows.put(row.series(), row);
        OffsetDateTime now = now();
        List<PlanningSourceStatus> result = new ArrayList<>();
        for (PlanningSeries series : PlanningSeries.values()) {
            SourceRow row = rows.get(series);
            if (row == null) {
                result.add(new PlanningSourceStatus(series, SourceAvailability.UNAVAILABLE,
                        BigDecimal.ZERO, null, null, FreshnessState.UNKNOWN,
                        List.of("No evidence-backed observation is available."), null,
                        List.of(), null, 0));
            } else {
                result.add(sourceStatus(row, now));
            }
        }
        return List.copyOf(result);
    }

    private PlanningSourceStatus sourceStatus(SourceRow row, OffsetDateTime evaluatedAt) {
        return new PlanningSourceStatus(row.series(), row.availability(), row.coveragePercent(),
                row.sourceAt(), row.receivedAt(), freshness(row.sourceAt(), row.receivedAt(), evaluatedAt),
                List.copyOf(row.exclusions()), row.evidenceReference(), List.copyOf(row.points()),
                row.observationId(), row.sequence());
    }

    ForecastProjection forecast(
            long tenantId, PlanningScope scope, List<PlanningSourceStatus> statuses) {
        OffsetDateTime evaluatedAt = now();
        ForecastRow row = repository.latestForecast(tenantId, scope).orElse(null);
        if (row == null) {
            return unavailableForecast(ForecastState.DATA_INSUFFICIENT,
                    List.of("No evidence-backed forecast is available."), evaluatedAt);
        }
        if (row.state() != ForecastState.READY) {
            return projection(row, row.state(), List.of(), null, row.limitations(), evaluatedAt);
        }

        List<String> limitations = new ArrayList<>(row.limitations());
        ForecastState derived = derivedForecastState(tenantId, scope, statuses, row, limitations,
                evaluatedAt);
        if (derived != ForecastState.READY) {
            return projection(row, derived, List.of(), null, limitations, evaluatedAt);
        }
        return projection(row, ForecastState.READY, row.points(), row.recommendationMetrics(),
                limitations, evaluatedAt);
    }

    private ForecastState derivedForecastState(
            long tenantId,
            PlanningScope scope,
            List<PlanningSourceStatus> statuses,
            ForecastRow row,
            List<String> limitations,
            OffsetDateTime evaluatedAt) {
        if (row.receivedAt().isAfter(evaluatedAt.plus(CLOCK_SKEW))
                || row.sourceAt().isAfter(row.receivedAt())
                || freshness(row.sourceAt(), row.receivedAt(), evaluatedAt) != FreshnessState.FRESH) {
            limitations.add("The forecast observation is stale or has an invalid source clock.");
            return ForecastState.STALE;
        }
        if (row.points().isEmpty() || row.recommendationMetrics() == null
                || normalized(row.calculationVersion()) == null
                || normalized(row.evidenceReference()) == null) {
            limitations.add("The forecast lacks calculation evidence or recommendation metrics.");
            return ForecastState.COMPUTE_FAILED;
        }
        if (row.sourceObservationIds().size() != PlanningSeries.values().length
                || new HashSet<>(row.sourceObservationIds()).size() != PlanningSeries.values().length) {
            limitations.add("The forecast does not reference one observation from every required series.");
            return ForecastState.DATA_INSUFFICIENT;
        }
        List<SourceRow> referenced = repository.sourcesByIds(tenantId, row.sourceObservationIds());
        if (referenced.size() != PlanningSeries.values().length
                || !referenced.stream().allMatch(source -> sameScope(scope, source.scope()))) {
            limitations.add("One or more referenced source observations are missing or out of scope.");
            return ForecastState.DATA_INSUFFICIENT;
        }
        EnumSet<PlanningSeries> series = EnumSet.noneOf(PlanningSeries.class);
        for (SourceRow source : referenced) series.add(source.series());
        if (series.size() != PlanningSeries.values().length) {
            limitations.add("The forecast source set does not cover every required series.");
            return ForecastState.DATA_INSUFFICIENT;
        }
        if (referenced.stream().anyMatch(source -> source.availability() == SourceAvailability.COMPUTE_FAILED)) {
            limitations.add("A referenced source reports a computation failure.");
            return ForecastState.COMPUTE_FAILED;
        }
        if (referenced.stream().anyMatch(source -> freshness(
                source.sourceAt(), source.receivedAt(), evaluatedAt) != FreshnessState.FRESH)) {
            limitations.add("At least one referenced source is stale.");
            return ForecastState.STALE;
        }
        if (referenced.stream().anyMatch(source -> source.availability() == SourceAvailability.PARTIAL
                || source.coveragePercent().compareTo(FULL_COVERAGE) < 0)) {
            limitations.add("At least one referenced source has partial coverage.");
            return ForecastState.PARTIAL;
        }
        if (referenced.stream().anyMatch(source -> source.availability() != SourceAvailability.AVAILABLE
                || normalized(source.evidenceReference()) == null)) {
            limitations.add("At least one required source is unavailable or lacks evidence.");
            return ForecastState.DATA_INSUFFICIENT;
        }
        Set<UUID> latest = new HashSet<>();
        for (PlanningSourceStatus status : statuses) {
            if (status.observationId() != null) latest.add(status.observationId());
        }
        if (!latest.equals(new HashSet<>(row.sourceObservationIds()))) {
            limitations.add("Newer source evidence exists; recompute the forecast before using it.");
            return ForecastState.STALE;
        }
        return ForecastState.READY;
    }

    private ForecastProjection unavailableForecast(
            ForecastState state, List<String> limitations, OffsetDateTime evaluatedAt) {
        return new ForecastProjection(null, state, null, null, List.of(), List.of(), null,
                List.copyOf(limitations), null, null, evaluatedAt);
    }

    private ForecastProjection projection(
            ForecastRow row,
            ForecastState state,
            List<ForecastPoint> points,
            RecommendationMetrics metrics,
            List<String> limitations,
            OffsetDateTime evaluatedAt) {
        boolean ready = state == ForecastState.READY;
        return new ForecastProjection(row.forecastId(), state, row.calculationVersion(),
                row.evidenceReference(), List.copyOf(row.sourceObservationIds()),
                ready ? List.copyOf(points) : List.of(), ready ? metrics : null,
                List.copyOf(limitations), row.sourceAt(), row.receivedAt(), evaluatedAt);
    }

    SpaceComparison comparison(
            CurrentSpaceMetrics current,
            ScenarioDraftInput proposed,
            ForecastProjection forecast,
            Integer impactedBookingCount) {
        BigDecimal peak = forecast.state() == ForecastState.READY
                && forecast.recommendationMetrics() != null
                ? forecast.recommendationMetrics().peakDemand() : null;
        return new SpaceComparison(current.capacity(), proposed.proposedCapacity(),
                current.roomCapacity(), proposed.proposedRoomCapacity(),
                current.accessibleResourceCount(), proposed.proposedAccessibleResourceCount(),
                utilization(peak, current.capacity()), utilization(peak, proposed.proposedCapacity()),
                peak, excess(peak, current.capacity()), excess(peak, proposed.proposedCapacity()),
                impactedBookingCount);
    }

    BookingImpactState impactState(ScenarioRow scenario, CurrentSpaceMetrics current) {
        return scenario.draft().affectedResourceIds().isEmpty()
                ? BookingImpactState.SCOPE_INCOMPLETE : BookingImpactState.READY;
    }

    EmissionProjection selectedEmission(long tenantId, ScenarioRow scenario) {
        UUID evidenceId = scenario.draft().emissionEvidenceId();
        if (evidenceId == null) return null;
        if (!repository.emissionBelongsToScope(tenantId, evidenceId, scenario.scope())) {
            throw conflict("The selected emission evidence no longer belongs to the scenario scope.");
        }
        return repository.emission(tenantId, evidenceId)
                .orElseThrow(() -> conflict("The selected emission evidence is unavailable."));
    }


    PlanningScope validateScope(long tenantId, PlanningScope scope) {
        validateTrustedScope(tenantId, scope);
        return canonicalScope(scope);
    }

    private void validateTrustedScope(long tenantId, PlanningScope scope) {
        if (scope == null || scope.siteId() == null || scope.from() == null || scope.to() == null) {
            throw invalid("A complete site and planning window are required.");
        }
        if (!scope.to().isAfter(scope.from())
                || Duration.between(scope.from(), scope.to()).compareTo(Duration.ofDays(366)) > 0) {
            throw invalid("The planning window must be positive and no longer than 366 days.");
        }
        if (scope.resourceType() != null
                && !RESOURCE_TYPES.contains(scope.resourceType().trim().toUpperCase(Locale.ROOT))) {
            throw invalid("The resource type is not supported by space planning.");
        }
        if (scope.neighborhood() != null && scope.neighborhood().trim().length() > 120) {
            throw invalid("The neighborhood is too long.");
        }
        if (!repository.siteExists(tenantId, scope.siteId())) {
            throw notFound("The space-planning site was not found in this tenant.");
        }
        if (scope.floorId() != null
                && !repository.floorBelongsToSite(tenantId, scope.siteId(), scope.floorId())) {
            throw invalid("The selected floor does not belong to the scenario site and tenant.");
        }
    }

    ScenarioDraftInput validateDraft(
            long tenantId, PlanningScope scope, ScenarioDraftInput draft) {
        if (draft == null || draft.operatingStart() == null || draft.operatingEnd() == null
                || draft.affectedResourceIds() == null || draft.neighborhoodAllocations() == null) {
            throw invalid("A complete scenario draft is required.");
        }
        if (draft.proposedCapacity() < 0 || draft.proposedRoomCapacity() < 0
                || draft.proposedAccessibleResourceCount() < 0
                || draft.proposedRoomCapacity() > draft.proposedCapacity()
                || draft.proposedAccessibleResourceCount() > draft.proposedCapacity()) {
            throw invalid("Proposed capacity and accessibility counts are inconsistent.");
        }
        if (!draft.operatingEnd().isAfter(draft.operatingStart())) {
            throw invalid("Proposed operating end time must be after the start time.");
        }
        if (draft.policyReference() != null && draft.policyReference().trim().length() > 320) {
            throw invalid("The policy reference is too long.");
        }
        List<UUID> requestedResources = draft.affectedResourceIds();
        if (requestedResources.size() > 5000
                || requestedResources.stream().anyMatch(Objects::isNull)
                || new HashSet<>(requestedResources).size() != requestedResources.size()) {
            throw invalid("Affected resource identifiers must be non-null and unique.");
        }
        List<UUID> affected = List.copyOf(requestedResources);
        if (!repository.resourcesBelongToScope(tenantId, scope, affected)) {
            throw invalid("At least one affected resource is outside the scenario scope or tenant.");
        }
        List<NeighborhoodAllocationInput> allocations = draft.neighborhoodAllocations();
        if (allocations.size() > 500) throw invalid("Too many neighborhood allocations were supplied.");
        Set<String> names = new HashSet<>();
        long allocatedCapacity = 0;
        List<NeighborhoodAllocationInput> normalizedAllocations = new ArrayList<>();
        for (NeighborhoodAllocationInput allocation : allocations) {
            if (allocation == null || normalized(allocation.neighborhood()) == null
                    || allocation.capacity() < 0) {
                throw invalid("Neighborhood allocations require a name and non-negative capacity.");
            }
            String name = allocation.neighborhood().trim();
            if (name.length() > 120 || !names.add(name.toLowerCase(Locale.ROOT))) {
                throw invalid("Neighborhood allocation names must be unique and at most 120 characters.");
            }
            allocatedCapacity += allocation.capacity();
            normalizedAllocations.add(new NeighborhoodAllocationInput(name, allocation.capacity()));
        }
        if (!allocations.isEmpty() && allocatedCapacity != draft.proposedCapacity()) {
            throw invalid("Neighborhood allocation capacity must equal proposed capacity.");
        }
        if (draft.emissionEvidenceId() != null
                && !repository.emissionBelongsToScope(
                tenantId, draft.emissionEvidenceId(), scope)) {
            throw invalid("The selected emission evidence is outside the scenario scope or tenant.");
        }
        return new ScenarioDraftInput(draft.proposedCapacity(), draft.proposedRoomCapacity(),
                draft.proposedAccessibleResourceCount(), draft.operatingStart(), draft.operatingEnd(),
                normalized(draft.policyReference()), affected, List.copyOf(normalizedAllocations),
                draft.emissionEvidenceId());
    }

    private void validateSourceObservation(SourceObservation observation) {
        if (observation.series() == null || observation.availability() == null
                || observation.coveragePercent() == null || observation.sourceAt() == null
                || observation.receivedAt() == null || observation.exclusions() == null
                || observation.points() == null || observation.observationId() == null
                || normalized(observation.evidenceReference()) == null
                || observation.sequence() < 1) {
            throw invalid("The source observation is incomplete.");
        }
        validateClock(observation.sourceAt(), observation.receivedAt());
        if (observation.coveragePercent().compareTo(BigDecimal.ZERO) < 0
                || observation.coveragePercent().compareTo(FULL_COVERAGE) > 0) {
            throw invalid("Source coverage must be between zero and one hundred percent.");
        }
        if (observation.availability() == SourceAvailability.AVAILABLE
                && observation.coveragePercent().compareTo(FULL_COVERAGE) != 0) {
            throw invalid("Available source observations require full coverage.");
        }
        if (observation.availability() == SourceAvailability.PARTIAL
                && (observation.coveragePercent().compareTo(BigDecimal.ZERO) <= 0
                || observation.coveragePercent().compareTo(FULL_COVERAGE) >= 0)) {
            throw invalid("Partial source observations require partial coverage.");
        }
        if ((observation.availability() == SourceAvailability.UNAVAILABLE
                || observation.availability() == SourceAvailability.COMPUTE_FAILED)
                && (observation.coveragePercent().compareTo(BigDecimal.ZERO) != 0
                || !observation.points().isEmpty())) {
            throw invalid("Unavailable or failed sources cannot expose measurement points.");
        }
        if ((observation.availability() == SourceAvailability.AVAILABLE
                || observation.availability() == SourceAvailability.PARTIAL)
                && observation.points().isEmpty()) {
            throw invalid("Available or partial sources require evidence-backed measurement points.");
        }
        if (observation.points().size() > 20_000
                || observation.exclusions().size() > 100
                || observation.exclusions().stream().anyMatch(value -> normalized(value) == null
                || value.trim().length() > 300)
                || observation.points().stream().anyMatch(point -> !valid(point, observation.scope()))) {
            throw invalid("The source measurement points are invalid or outside the planning window.");
        }
        if (normalized(observation.payloadFingerprint()) == null
                || !observation.payloadFingerprint().matches("^[A-Za-z0-9:_-]{8,128}$")) {
            throw invalid("A valid source payload fingerprint is required.");
        }
    }

    private void validateForecastObservation(ForecastObservation observation) {
        if (observation.forecastId() == null || observation.state() == null
                || observation.sourceObservationIds() == null || observation.points() == null
                || observation.limitations() == null || observation.sourceAt() == null
                || observation.receivedAt() == null) {
            throw invalid("The forecast observation is incomplete.");
        }
        validateClock(observation.sourceAt(), observation.receivedAt());
        if (observation.sourceObservationIds().size() > PlanningSeries.values().length
                || observation.sourceObservationIds().stream().anyMatch(Objects::isNull)
                || observation.limitations().size() > 100
                || observation.limitations().stream().anyMatch(value -> normalized(value) == null
                || value.trim().length() > 300)) {
            throw invalid("Forecast source identifiers or limitations are invalid.");
        }
        if (observation.state() != ForecastState.READY) {
            if (!observation.points().isEmpty() || observation.recommendationMetrics() != null) {
                throw invalid("Non-ready forecasts must suppress points and recommendation metrics.");
            }
            return;
        }
        if (normalized(observation.calculationVersion()) == null
                || normalized(observation.evidenceReference()) == null
                || observation.points().isEmpty() || observation.recommendationMetrics() == null
                || observation.sourceObservationIds().size() != PlanningSeries.values().length
                || new HashSet<>(observation.sourceObservationIds()).size()
                != PlanningSeries.values().length) {
            throw invalid("Ready forecasts require calculation evidence and all six source observations.");
        }
        List<SourceRow> sources = repository.sourcesByIds(
                observation.tenantId(), observation.sourceObservationIds());
        if (sources.size() != PlanningSeries.values().length
                || !sources.stream().allMatch(row -> sameScope(observation.scope(), row.scope()))) {
            throw invalid("Ready forecast source observations are missing or outside the forecast scope.");
        }
        EnumSet<PlanningSeries> kinds = EnumSet.noneOf(PlanningSeries.class);
        for (SourceRow source : sources) kinds.add(source.series());
        if (kinds.size() != PlanningSeries.values().length
                || sources.stream().anyMatch(source -> source.availability() != SourceAvailability.AVAILABLE
                || source.coveragePercent().compareTo(FULL_COVERAGE) != 0
                || normalized(source.evidenceReference()) == null
                || source.receivedAt().isAfter(observation.receivedAt())
                || freshness(source.sourceAt(), source.receivedAt(), observation.receivedAt())
                != FreshnessState.FRESH)) {
            throw invalid("Ready forecasts require fresh, fully covered, evidence-backed source series.");
        }
        if (observation.points().size() > 20_000
                || observation.points().stream().anyMatch(point -> !valid(point, observation.scope()))) {
            throw invalid("Forecast points are invalid or outside the planning window.");
        }
        RecommendationMetrics metrics = observation.recommendationMetrics();
        if (metrics.peakDemand() == null || metrics.lowUtilizationDemand() == null
                || metrics.confidencePercent() == null
                || normalized(metrics.calculationVersion()) == null
                || metrics.peakDemand().compareTo(BigDecimal.ZERO) < 0
                || metrics.lowUtilizationDemand().compareTo(BigDecimal.ZERO) < 0
                || metrics.confidencePercent().compareTo(BigDecimal.ZERO) < 0
                || metrics.confidencePercent().compareTo(FULL_COVERAGE) > 0
                || !metrics.calculationVersion().equals(observation.calculationVersion())) {
            throw invalid("Forecast recommendation metrics are invalid or use another calculation version.");
        }
    }

    private void validateEmissionObservation(EmissionObservation observation) {
        if (observation.emissionEvidenceId() == null || observation.evidenceKind() == null
                || observation.energyValue() == null || observation.co2eValue() == null
                || normalized(observation.energyUnit()) == null
                || normalized(observation.co2eUnit()) == null
                || normalized(observation.factorVersion()) == null
                || normalized(observation.regionCode()) == null
                || normalized(observation.evidenceReference()) == null
                || observation.sourceAt() == null || observation.receivedAt() == null) {
            throw invalid("Complete energy and CO2e evidence is required.");
        }
        validateClock(observation.sourceAt(), observation.receivedAt());
        if (observation.energyValue().compareTo(BigDecimal.ZERO) < 0
                || observation.co2eValue().compareTo(BigDecimal.ZERO) < 0) {
            throw invalid("Energy and CO2e values cannot be negative.");
        }
        if (observation.evidenceKind() == EmissionEvidenceKind.APPROVED_MODEL
                && (observation.approvedBy() == null || observation.approvedBy() < 1
                || normalized(observation.approvalAuthorityReference()) == null)) {
            throw invalid("Calculation-model emissions require a recorded approver and authority reference.");
        }
        if (observation.energyUnit().trim().length() > 24
                || observation.co2eUnit().trim().length() > 24
                || observation.factorVersion().trim().length() > 120
                || observation.regionCode().trim().length() > 80
                || observation.evidenceReference().trim().length() > 320
                || (observation.approvalAuthorityReference() != null
                && observation.approvalAuthorityReference().trim().length() > 160)) {
            throw invalid("Energy and CO2e evidence metadata exceeds its supported size.");
        }
    }

    private void validateClock(OffsetDateTime sourceAt, OffsetDateTime receivedAt) {
        if (sourceAt.isAfter(receivedAt) || receivedAt.isAfter(now().plus(CLOCK_SKEW))) {
            throw invalid("Source and received clocks are invalid.");
        }
    }

    private boolean valid(SeriesPoint point, PlanningScope scope) {
        if (point == null || point.bucketStart() == null || point.value() == null
                || normalized(point.unit()) == null || point.unit().trim().length() > 40
                || point.value().compareTo(BigDecimal.ZERO) < 0
                || point.bucketStart().isBefore(scope.from()) || !point.bucketStart().isBefore(scope.to())) {
            return false;
        }
        return (point.lowerBound() == null || point.lowerBound().compareTo(BigDecimal.ZERO) >= 0)
                && (point.upperBound() == null || point.upperBound().compareTo(BigDecimal.ZERO) >= 0)
                && (point.lowerBound() == null || point.lowerBound().compareTo(point.value()) <= 0)
                && (point.upperBound() == null || point.upperBound().compareTo(point.value()) >= 0);
    }

    private boolean valid(ForecastPoint point, PlanningScope scope) {
        if (point == null || point.bucketStart() == null || point.expectedDemand() == null
                || normalized(point.unit()) == null || point.unit().trim().length() > 40
                || point.expectedDemand().compareTo(BigDecimal.ZERO) < 0
                || point.bucketStart().isBefore(scope.from()) || !point.bucketStart().isBefore(scope.to())) {
            return false;
        }
        return (point.lowerBound() == null || point.lowerBound().compareTo(BigDecimal.ZERO) >= 0)
                && (point.upperBound() == null || point.upperBound().compareTo(BigDecimal.ZERO) >= 0)
                && (point.lowerBound() == null || point.lowerBound().compareTo(point.expectedDemand()) <= 0)
                && (point.upperBound() == null || point.upperBound().compareTo(point.expectedDemand()) >= 0);
    }

    private FreshnessState freshness(
            OffsetDateTime sourceAt, OffsetDateTime receivedAt, OffsetDateTime evaluatedAt) {
        if (sourceAt == null || receivedAt == null) return FreshnessState.UNKNOWN;
        if (sourceAt.isAfter(receivedAt) || receivedAt.isAfter(evaluatedAt.plus(CLOCK_SKEW))) {
            return FreshnessState.UNKNOWN;
        }
        return receivedAt.isBefore(evaluatedAt.minus(SOURCE_MAX_AGE))
                ? FreshnessState.STALE : FreshnessState.FRESH;
    }

    private static PlanningScope canonicalScope(PlanningScope scope) {
        return new PlanningScope(scope.siteId(), scope.floorId(), normalized(scope.neighborhood()),
                scope.resourceType() == null ? null
                        : scope.resourceType().trim().toUpperCase(Locale.ROOT),
                scope.from(), scope.to());
    }

    private static boolean sameScope(PlanningScope left, PlanningScope right) {
        PlanningScope a = canonicalScope(left);
        PlanningScope b = canonicalScope(right);
        return Objects.equals(a.siteId(), b.siteId()) && Objects.equals(a.floorId(), b.floorId())
                && Objects.equals(a.neighborhood(), b.neighborhood())
                && Objects.equals(a.resourceType(), b.resourceType())
                && Objects.equals(a.from(), b.from()) && Objects.equals(a.to(), b.to());
    }

    private static BigDecimal utilization(BigDecimal demand, int capacity) {
        if (demand == null || capacity <= 0) return null;
        return demand.multiply(FULL_COVERAGE)
                .divide(BigDecimal.valueOf(capacity), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal excess(BigDecimal demand, int capacity) {
        if (demand == null) return null;
        return demand.subtract(BigDecimal.valueOf(capacity)).max(BigDecimal.ZERO);
    }


    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static void requireTenant(long tenantId) {
        if (tenantId < 1) throw new BaseException(ErrorCode.TENANT_MISSING,
                "A positive tenant identifier is required.");
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private static BaseException notFound(String message) {
        return new BaseException(ErrorCode.NOT_FOUND, message);
    }
}
