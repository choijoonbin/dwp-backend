package com.dwp.services.platform.workplace;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class WorkplaceSpacePlanningBoardReportDtos {
    private WorkplaceSpacePlanningBoardReportDtos() { }

    public enum ReportFormat { PDF, XLSX }
    public enum ReportCommandState { SUCCEEDED, FAILED, RESULT_UNKNOWN }

    @Schema(name = "WorkplaceSpacePlanningBoardReportPreviewRequest")
    public record PreviewRequest(
            @NotNull UUID scenarioId,
            @NotNull @Min(1) Long expectedScenarioVersion,
            @NotNull ReportFormat format,
            @NotBlank @Size(max = 500) String reason) { }

    @Schema(name = "WorkplaceSpacePlanningBoardReportExecuteRequest")
    public record ExecuteRequest(
            @NotNull UUID previewId,
            @NotNull @Min(1) Long expectedPreviewVersion,
            @NotNull @Min(1) Long expectedScenarioVersion,
            @NotBlank @Size(max = 80) String confirmationToken,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    @Schema(name = "WorkplaceSpacePlanningBoardReportSnapshot")
    public record ReportSnapshot(
            UUID scenarioId,
            long scenarioVersion,
            String scenarioName,
            String scenarioState,
            UUID siteId,
            String siteCode,
            String siteName,
            UUID floorId,
            String floorName,
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd,
            int currentCapacity,
            int proposedCapacity,
            int currentRoomCapacity,
            int proposedRoomCapacity,
            int currentAccessibleResourceCount,
            int proposedAccessibleResourceCount,
            BigDecimal currentUtilizationPercent,
            BigDecimal proposedUtilizationPercent,
            BigDecimal peakDemand,
            BigDecimal forecastConfidencePercent,
            String forecastState,
            String calculationVersion,
            BigDecimal energyValue,
            String energyUnit,
            BigDecimal co2eValue,
            String co2eUnit,
            String emissionFactorVersion,
            String emissionRegionCode,
            int affectedResourceCount,
            Integer impactedBookingCount,
            boolean personLevelDataIncluded,
            int personLevelRowCount,
            OffsetDateTime capturedAt) { }

    @Schema(name = "WorkplaceSpacePlanningBoardReportPreview")
    public record ReportPreview(
            UUID previewId,
            UUID scenarioId,
            UUID siteId,
            UUID floorId,
            ReportFormat format,
            long scenarioVersion,
            long previewVersion,
            String confirmationToken,
            String snapshotSha256,
            ReportSnapshot snapshot,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            boolean idempotentReplay) { }

    @Schema(name = "WorkplaceSpacePlanningBoardReportReceipt")
    public record ReportReceipt(
            UUID commandId,
            UUID previewId,
            UUID scenarioId,
            UUID siteId,
            UUID floorId,
            ReportFormat format,
            ReportCommandState state,
            long scenarioVersion,
            long commandVersion,
            String mimeType,
            String fileName,
            long byteSize,
            String contentSha256,
            String contentHref,
            OffsetDateTime acceptedAt,
            OffsetDateTime completedAt,
            OffsetDateTime expiresAt,
            boolean idempotentReplay,
            String correlationId) { }

    public record ReportContent(
            ReportReceipt receipt,
            byte[] payload) {
        public ReportContent {
            payload = payload == null ? new byte[0] : payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
