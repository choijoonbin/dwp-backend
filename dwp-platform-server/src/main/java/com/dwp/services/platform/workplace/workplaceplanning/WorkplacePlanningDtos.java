package com.dwp.services.platform.workplace.workplaceplanning;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class WorkplacePlanningDtos {
    private WorkplacePlanningDtos() { }

    public enum PlanningSeries {
        WORK_PLAN, RESERVATION, CHECK_IN, ACCESS, SENSOR_OCCUPANCY, NO_SHOW
    }

    public enum SourceAvailability { AVAILABLE, PARTIAL, UNAVAILABLE, COMPUTE_FAILED }
    public enum FreshnessState { FRESH, STALE, UNKNOWN }
    public enum ForecastState { READY, DATA_INSUFFICIENT, STALE, PARTIAL, COMPUTE_FAILED }
    public enum ScenarioState { DRAFT, PREVIEWED, SUBMITTED, APPROVED, PUBLISHED }
    public enum ApprovalDecision { APPROVE, REJECT }
    public enum BookingImpactState { READY, SCOPE_INCOMPLETE, COMPUTE_FAILED }
    public enum EmissionEvidenceKind { METER, APPROVED_MODEL }
    public enum CommandState { SUCCEEDED, FAILED, RESULT_UNKNOWN }
    public enum OutboxState { PENDING, PUBLISHED, FAILED, RESULT_UNKNOWN }

    public record PlanningScope(
            @NotNull UUID siteId,
            UUID floorId,
            @Size(max = 120) String neighborhood,
            @Pattern(regexp = "ROOM|DESK|LOCKER|PARKING|FOCUS_POD|PHONE_BOOTH|EQUIPMENT")
            String resourceType,
            @NotNull OffsetDateTime from,
            @NotNull OffsetDateTime to) { }

    public record SeriesPoint(
            @NotNull OffsetDateTime bucketStart,
            @NotNull @DecimalMin("0") BigDecimal value,
            @DecimalMin("0") BigDecimal lowerBound,
            @DecimalMin("0") BigDecimal upperBound,
            @NotBlank @Size(max = 40) String unit) { }

    /** Trusted adapter input. It has no administrator HTTP route. */
    public record SourceObservation(
            @NotNull UUID observationId,
            @Min(1) long tenantId,
            @NotNull PlanningSeries series,
            @Valid @NotNull PlanningScope scope,
            @NotNull SourceAvailability availability,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal coveragePercent,
            @NotNull OffsetDateTime sourceAt,
            @NotNull OffsetDateTime receivedAt,
            @NotBlank @Size(max = 320) String evidenceReference,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 300) String> exclusions,
            @Valid @NotNull @Size(max = 20_000) List<SeriesPoint> points,
            @Min(1) long sequence,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9:_-]{8,128}$") String payloadFingerprint) { }

    public record PlanningSourceStatus(
            PlanningSeries series,
            SourceAvailability availability,
            BigDecimal coveragePercent,
            OffsetDateTime sourceAt,
            OffsetDateTime receivedAt,
            FreshnessState freshness,
            List<String> exclusions,
            String evidenceReference,
            List<SeriesPoint> points,
            UUID observationId,
            long sequence) { }

    public record ForecastPoint(
            OffsetDateTime bucketStart,
            BigDecimal expectedDemand,
            BigDecimal lowerBound,
            BigDecimal upperBound,
            String unit) { }

    public record RecommendationMetrics(
            BigDecimal peakDemand,
            BigDecimal lowUtilizationDemand,
            BigDecimal confidencePercent,
            String calculationVersion) { }

    /** Trusted forecasting adapter input. Non-ready states must carry no points or metrics. */
    public record ForecastObservation(
            @NotNull UUID forecastId,
            @Min(1) long tenantId,
            @Valid @NotNull PlanningScope scope,
            @NotNull ForecastState state,
            @Size(max = 120) String calculationVersion,
            @Size(max = 320) String evidenceReference,
            @NotNull @Size(max = 6) List<UUID> sourceObservationIds,
            @Valid @NotNull @Size(max = 20_000) List<ForecastPoint> points,
            RecommendationMetrics recommendationMetrics,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 300) String> limitations,
            @NotNull OffsetDateTime sourceAt,
            @NotNull OffsetDateTime receivedAt) { }

    public record ForecastProjection(
            UUID forecastId,
            ForecastState state,
            String calculationVersion,
            String evidenceReference,
            List<UUID> sourceObservationIds,
            List<ForecastPoint> points,
            RecommendationMetrics recommendationMetrics,
            List<String> limitations,
            OffsetDateTime sourceAt,
            OffsetDateTime receivedAt,
            OffsetDateTime evaluatedAt) { }

    /** Trusted meter or approved-model input. It has no administrator HTTP route. */
    public record EmissionObservation(
            @NotNull UUID emissionEvidenceId,
            @Min(1) long tenantId,
            @NotNull UUID siteId,
            UUID floorId,
            @NotNull EmissionEvidenceKind evidenceKind,
            @NotNull @DecimalMin("0") BigDecimal energyValue,
            @NotBlank @Size(max = 24) String energyUnit,
            @NotNull @DecimalMin("0") BigDecimal co2eValue,
            @NotBlank @Size(max = 24) String co2eUnit,
            @NotBlank @Size(max = 120) String factorVersion,
            @NotBlank @Size(max = 80) String regionCode,
            @NotBlank @Size(max = 320) String evidenceReference,
            @NotNull OffsetDateTime sourceAt,
            @NotNull OffsetDateTime receivedAt,
            Long approvedBy,
            @Size(max = 160) String approvalAuthorityReference) { }

    public record EmissionProjection(
            UUID emissionEvidenceId,
            EmissionEvidenceKind evidenceKind,
            BigDecimal energyValue,
            String energyUnit,
            BigDecimal co2eValue,
            String co2eUnit,
            String factorVersion,
            String regionCode,
            String evidenceReference,
            OffsetDateTime sourceAt,
            OffsetDateTime receivedAt) { }

    public record NeighborhoodAllocationInput(
            @NotBlank @Size(max = 120) String neighborhood,
            @Min(0) int capacity) { }

    public record ScenarioDraftInput(
            @Min(0) int proposedCapacity,
            @Min(0) int proposedRoomCapacity,
            @Min(0) int proposedAccessibleResourceCount,
            @NotNull LocalTime operatingStart,
            @NotNull LocalTime operatingEnd,
            @Size(max = 320) String policyReference,
            @NotNull @Size(max = 5000) List<UUID> affectedResourceIds,
            @Valid @NotNull @Size(max = 500) List<NeighborhoodAllocationInput> neighborhoodAllocations,
            UUID emissionEvidenceId) { }

    public record CreateScenarioRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 1000) String description,
            @Valid @NotNull PlanningScope scope,
            @Valid @NotNull ScenarioDraftInput draft,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record UpdateScenarioRequest(
            @Min(1) long expectedVersion,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 1000) String description,
            @Valid @NotNull ScenarioDraftInput draft,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record PreviewScenarioRequest(
            @Min(1) long expectedVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record ScenarioTransitionRequest(
            @Min(1) long expectedVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record ScenarioApprovalRequest(
            @Min(1) long expectedVersion,
            @NotNull ApprovalDecision decision,
            @NotBlank @Size(max = 160) String approvalAuthorityReference,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record BookingImpactPreviewRequest(
            @Min(1) long expectedVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record CurrentSpaceMetrics(
            int capacity,
            int roomCapacity,
            int accessibleResourceCount,
            int resourceCount,
            OffsetDateTime catalogAsOf) { }

    public record SpaceComparison(
            int currentCapacity,
            int proposedCapacity,
            int currentRoomCapacity,
            int proposedRoomCapacity,
            int currentAccessibleResourceCount,
            int proposedAccessibleResourceCount,
            BigDecimal currentUtilizationPercent,
            BigDecimal proposedUtilizationPercent,
            BigDecimal peakDemand,
            BigDecimal currentExcessDemand,
            BigDecimal proposedExcessDemand,
            Integer impactedBookingCount) { }

    public record BookingImpactItem(
            UUID bookingId,
            UUID resourceId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String bookingStatus,
            String impactReason,
            boolean automaticallyMoved) { }

    public record BookingImpactPreview(
            UUID impactPreviewId,
            UUID scenarioId,
            long scenarioVersion,
            BookingImpactState state,
            Integer impactedBookingCount,
            List<BookingImpactItem> bookings,
            List<String> limitations,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt) { }

    public record ScenarioPreview(
            UUID previewId,
            UUID scenarioId,
            long scenarioVersion,
            ForecastState forecastState,
            ForecastProjection forecast,
            SpaceComparison comparison,
            EmissionProjection emission,
            boolean eligible,
            List<String> limitations,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt) { }

    public record ScenarioView(
            UUID scenarioId,
            String name,
            String description,
            ScenarioState state,
            PlanningScope scope,
            ScenarioDraftInput draft,
            ScenarioPreview activePreview,
            long version,
            OffsetDateTime submittedAt,
            Long submittedBy,
            OffsetDateTime approvedAt,
            Long approvedBy,
            String approvalAuthorityReference,
            OffsetDateTime publishedAt,
            Long publishedBy,
            OffsetDateTime lastRejectedAt,
            Long lastRejectedBy,
            String lastRejectionReason,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) { }

    public record CommandReceipt(
            UUID commandId,
            String commandType,
            CommandState state,
            UUID scenarioId,
            ScenarioState scenarioState,
            long scenarioVersion,
            UUID outboxId,
            OutboxState outboxState,
            boolean idempotentReplay,
            String correlationId,
            OffsetDateTime acceptedAt) { }

    public record ScenarioCommandResult(ScenarioView scenario, CommandReceipt receipt) { }

    public record BookingImpactCommandResult(
            BookingImpactPreview preview,
            CommandReceipt receipt) { }

    public record PlanningOverview(
            PlanningScope scope,
            CurrentSpaceMetrics current,
            List<PlanningSourceStatus> sources,
            ForecastProjection forecast,
            EmissionProjection emission,
            List<ScenarioView> scenarios,
            OffsetDateTime generatedAt) { }
}
