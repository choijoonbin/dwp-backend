package com.dwp.services.platform.workplace.workplacenavigation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkplaceNavigationDtos {
    private WorkplaceNavigationDtos() { }

    public enum GraphState { DRAFT, REVIEW, PUBLISHED, ARCHIVED }
    public enum NodeKind { ENTRY, JUNCTION, POI, ELEVATOR, STAIR, RAMP, HELP_DESK }
    public enum TravelMode { WALK, STAIR, ELEVATOR, RAMP }
    public enum PoiCategory {
        ROOM, ELEVATOR, STAIR, RESTROOM, PRINTER, HELP_DESK, AED,
        EMERGENCY_EXIT, ASSEMBLY_POINT, ENTRY, OTHER
    }
    public enum RouteOutcome { GUIDED, GRAPH_MISSING, GRAPH_STALE, ACCESS_DENIED, NO_ROUTE }
    public enum DeviceType { ROOM_PANEL, STATUS_BOARD }
    public enum RegistrationState { PENDING, APPROVED, BOUND, SUSPENDED, RETIRED }
    public enum ConnectivityState { UNREGISTERED, ONLINE, OFFLINE }
    public enum FreshnessState { UNKNOWN, FRESH, STALE }
    public enum AvailabilityState { AVAILABLE, OCCUPIED, UNAVAILABLE }
    public enum ProviderCapability { MDM, GRAPH, BLE, NFC, SPEED_GATE, SENSOR, MTLS, TPM }
    public enum ProviderReportedState { HEALTHY, DEGRADED, UNAVAILABLE }
    public enum ProviderTruthState {
        NOT_CONFIGURED, CONFIGURED_UNVERIFIED, HEALTHY, DEGRADED, STALE
    }
    public enum DeviceCommandType {
        FORCE_SYNC, CLEAR_CACHE, REBOOT, SAFETY_TAKEOVER, CLEAR_SAFETY, UNBIND
    }
    public enum DeviceCommandState { ACCEPTED, RUNNING, SUCCEEDED, FAILED, RESULT_UNKNOWN }
    public enum SafetyFrameState { ACTIVE, CLEARED }

    public record NavigationNodeInput(
            @NotNull UUID nodeId,
            @NotNull UUID floorId,
            @NotBlank @Size(max = 80) String nodeCode,
            @NotNull NodeKind nodeKind,
            @NotNull BigDecimal positionX,
            @NotNull BigDecimal positionY,
            boolean accessible,
            UUID restrictedZoneId) { }

    public record NavigationEdgeInput(
            @NotNull UUID edgeId,
            @NotNull UUID fromNodeId,
            @NotNull UUID toNodeId,
            @Min(1) int travelSeconds,
            @NotNull TravelMode travelMode,
            boolean bidirectional,
            boolean accessible,
            @Size(max = 120) String requiredPermission) { }

    public record PoiInput(
            @NotNull UUID poiId,
            @NotNull UUID nodeId,
            @NotNull UUID floorId,
            UUID resourceId,
            @NotNull PoiCategory category,
            @NotBlank @Size(max = 160) String nameKo,
            @NotBlank @Size(max = 160) String nameEn,
            @Size(max = 300) String directionHintKo,
            @Size(max = 300) String directionHintEn) { }

    public record CreateGraphRequest(
            @NotNull UUID siteId,
            @Min(1) long revisionNumber,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String contentHash,
            @NotBlank @Size(max = 500) String changeSummary,
            @Valid @NotEmpty @Size(max = 5000) List<NavigationNodeInput> nodes,
            @Valid @NotEmpty @Size(max = 10000) List<NavigationEdgeInput> edges,
            @Valid @NotEmpty @Size(max = 5000) List<PoiInput> pois,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record PublishGraphRequest(
            @Min(1) long expectedVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record GraphTransitionRequest(
            @Min(1) long expectedVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record GraphRevisionView(
            UUID graphRevisionId,
            UUID siteId,
            long revisionNumber,
            GraphState state,
            String contentHash,
            String changeSummary,
            long version,
            OffsetDateTime submittedAt,
            OffsetDateTime publishedAt,
            int nodeCount,
            int edgeCount,
            int poiCount,
            OffsetDateTime updatedAt) { }

    public record PoiView(
            UUID poiId,
            UUID nodeId,
            UUID siteId,
            UUID floorId,
            UUID resourceId,
            PoiCategory category,
            String nameKo,
            String nameEn,
            String directionHintKo,
            String directionHintEn) { }

    public record RouteStep(
            UUID fromNodeId,
            UUID toNodeId,
            UUID floorId,
            TravelMode travelMode,
            int travelSeconds,
            String directionKo,
            String directionEn) { }

    public record LocationFallback(
            UUID siteId,
            UUID floorId,
            UUID resourceId,
            String siteName,
            String floorName,
            String resourceName,
            String floorMapPath,
            List<PoiView> helpDesks) { }

    public record RouteProjection(
            RouteOutcome outcome,
            UUID graphRevisionId,
            long graphRevisionNumber,
            PoiView origin,
            PoiView destination,
            List<RouteStep> steps,
            int totalTravelSeconds,
            LocationFallback fallback,
            List<String> limitations,
            OffsetDateTime graphPublishedAt,
            OffsetDateTime asOf) { }

    public record DeviceRegistrationRequest(
            @NotBlank @Size(max = 160) String displayName,
            @NotNull DeviceType deviceType,
            @NotBlank @Size(max = 160) String hardwareModel,
            @NotBlank @Size(max = 80) String osVersion) { }

    public record DeviceHeartbeatRequest(
            @Min(1) long expectedVersion,
            @NotBlank @Size(max = 80) String appVersion,
            @NotBlank @Size(max = 80) String policyVersion,
            @NotNull OffsetDateTime observedAt,
            OffsetDateTime scheduleSourceAt,
            @Size(max = 120) String recentErrorCode) { }

    public record VersionedAdminCommand(
            @Min(1) long expectedVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record BindDeviceRequest(
            @Min(1) long expectedVersion,
            @NotNull UUID siteId,
            @NotNull UUID floorId,
            UUID resourceId,
            boolean safetyOfflineFallback,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record DeviceView(
            UUID deviceId,
            String displayName,
            DeviceType deviceType,
            RegistrationState registrationState,
            UUID siteId,
            UUID floorId,
            UUID resourceId,
            String hardwareModel,
            String osVersion,
            String appVersion,
            String policyVersion,
            OffsetDateTime heartbeatAt,
            ConnectivityState connectivity,
            OffsetDateTime scheduleSourceAt,
            OffsetDateTime scheduleReceivedAt,
            FreshnessState scheduleFreshness,
            String recentErrorCode,
            boolean safetyOfflineFallback,
            long version,
            OffsetDateTime updatedAt) { }

    public record ProviderTruth(
            ProviderCapability capability,
            String providerCode,
            ProviderTruthState state,
            long configurationVersion,
            Long observedConfigurationVersion,
            String evidenceReference,
            OffsetDateTime sourceAt,
            OffsetDateTime receivedAt,
            OffsetDateTime lastSuccessAt,
            String errorCode,
            long version,
            OffsetDateTime evaluatedAt) { }

    public record ProviderObservation(
            long tenantId,
            @NotNull ProviderCapability capability,
            @NotBlank @Size(max = 80) String providerCode,
            @Min(1) long observedConfigurationVersion,
            @NotNull ProviderReportedState reportedState,
            @NotBlank @Size(max = 320) String evidenceReference,
            @NotNull OffsetDateTime sourceAt,
            @NotNull OffsetDateTime receivedAt,
            OffsetDateTime lastSuccessAt,
            @Size(max = 120) String errorCode) { }

    public record ProviderConfigurationRequest(
            @NotBlank @Size(max = 80) String providerCode,
            @Min(1) long configurationVersion,
            boolean configured,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record ScheduleItem(
            UUID bookingId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String title,
            String organizer,
            boolean privacyMasked) { }

    public record SafetyFrame(
            UUID safetyFrameId,
            SafetyFrameState state,
            String message,
            String direction,
            OffsetDateTime issuedAt,
            long issuedByActorId,
            boolean offlineFallback,
            OffsetDateTime clearedAt,
            long version) { }

    public record RoomPanelProjection(
            DeviceView device,
            ScheduleItem current,
            ScheduleItem next,
            AvailabilityState availability,
            boolean walkUpBookingAllowed,
            boolean checkInAllowed,
            boolean earlyEndAllowed,
            SafetyFrame safetyFrame,
            OffsetDateTime asOf) { }

    public record FloorResourceStatus(
            UUID resourceId,
            UUID zoneId,
            String zoneNameKo,
            String zoneNameEn,
            String nameKo,
            String nameEn,
            AvailabilityState availability,
            String directionKo,
            String directionEn) { }

    public record StatusBoardProjection(
            DeviceView device,
            List<FloorResourceStatus> resources,
            int availableCount,
            int occupiedCount,
            int unavailableCount,
            SafetyFrame safetyFrame,
            FreshnessState freshness,
            OffsetDateTime asOf) { }

    public record DeviceProjection(
            DeviceType surface,
            RoomPanelProjection roomPanel,
            StatusBoardProjection statusBoard) { }

    public record DeviceCommandPreviewRequest(
            @NotNull DeviceCommandType commandType,
            @Min(1) long expectedDeviceVersion,
            @NotNull @Size(max = 12) Map<@NotBlank String, @Size(max = 1000) String> payload) { }

    public record DeviceCommandPreview(
            UUID previewId,
            UUID deviceId,
            DeviceCommandType commandType,
            long expectedDeviceVersion,
            Map<String, String> payload,
            List<String> impact,
            boolean eligible,
            List<String> limitations,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt) { }

    public record ExecuteDeviceCommandRequest(
            @NotNull UUID previewId,
            @Min(1) long expectedDeviceVersion,
            @NotBlank @Size(max = 500) String reason,
            boolean explicitConfirmation) { }

    public record DeviceCommandReceipt(
            UUID commandId,
            UUID deviceId,
            long actorUserId,
            DeviceCommandType commandType,
            DeviceCommandState state,
            String reason,
            String resultCode,
            String providerOperationReference,
            long version,
            boolean recoveryByGetOnly,
            String statusHref,
            String correlationId,
            OffsetDateTime acceptedAt,
            OffsetDateTime completedAt,
            OffsetDateTime updatedAt) { }

    public record DeviceAuditEvent(
            UUID auditEventId,
            long actorUserId,
            String action,
            String resourceType,
            UUID resourceId,
            String correlationId,
            OffsetDateTime occurredAt) { }

    record GraphRow(
            UUID graphRevisionId, long tenantId, UUID siteId, long revisionNumber,
            GraphState state, String contentHash, String changeSummary, long version,
            OffsetDateTime submittedAt, OffsetDateTime publishedAt, OffsetDateTime updatedAt) { }

    record NodeRow(
            UUID nodeId, UUID floorId, NodeKind kind, boolean accessible,
            UUID restrictedZoneId) { }

    record EdgeRow(
            UUID edgeId, UUID fromNodeId, UUID toNodeId, int travelSeconds,
            TravelMode travelMode, boolean bidirectional, boolean accessible,
            String requiredPermission) { }

    record ProviderTruthRow(
            long tenantId, ProviderCapability capability, String providerCode,
            long configurationVersion, Long observedConfigurationVersion,
            ProviderReportedState reportedState, String evidenceReference,
            OffsetDateTime sourceAt, OffsetDateTime receivedAt,
            OffsetDateTime lastSuccessAt, String errorCode, boolean configured,
            long version) { }

    record ScheduleRow(
            UUID bookingId, OffsetDateTime startsAt, OffsetDateTime endsAt,
            String purpose, String bookedForDisplayName, boolean visibleToColleagues) { }

    record CommandRow(
            UUID commandId, long tenantId, long actorUserId, UUID deviceId,
            UUID previewId, DeviceCommandType type, String idempotencyKey,
            String requestFingerprint, DeviceCommandState state, String reason,
            String correlationId, String providerOperationReference, String resultCode,
            String providerCode, Long providerConfigurationVersion,
            String credentialReference,
            long version, OffsetDateTime acceptedAt, OffsetDateTime completedAt,
            OffsetDateTime updatedAt) { }
}
