package com.dwp.services.platform.hrisconfiguration.explorer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class HrisExplorerDtos {

    private HrisExplorerDtos() {
    }

    public enum Freshness { FRESH_ONLY, ALLOW_STALE }

    public enum Module { HRM, PER, TIM, PAY, SYS }

    public enum Workbench {
        MY_HR, TEAM, HR_OPERATIONS, TIME, PAYROLL, PERFORMANCE, SETTINGS
    }

    public enum TargetKind { HRIS_INTERNAL, DWP_CONTROL }

    public enum CapabilityMode { SINGLE, ANY, ALL }

    public enum ProjectionState { FRESH, STALE, PARTIAL }

    public enum SourceKind {
        CATALOG, COMMAND_RECEIPT, WORKFLOW_CASE, APPROVAL_CASE,
        SERVICE_REQUEST, OPERATION_EXCEPTION
    }

    public enum ActivityState {
        PENDING, IN_PROGRESS, WAITING_FOR_ME, RESULT_UNKNOWN, FAILED
    }

    public enum DegradedReason { TIMEOUT, UNAVAILABLE, STALE }

    public enum ReceiptStatus {
        ACCEPTED, RUNNING, SUCCEEDED, REJECTED, FAILED, RESULT_UNKNOWN
    }

    public record ExplorerTarget(TargetKind kind, String path) {
    }

    public record ExplorerAuthorization(
            String routeContractKey,
            CapabilityMode capabilityMode,
            List<String> capabilityKeys) {

        public ExplorerAuthorization {
            capabilityKeys = capabilityKeys == null ? List.of() : List.copyOf(capabilityKeys);
        }
    }

    public record ExplorerRoute(
            String routeKey,
            ExplorerTarget target,
            String labelKey,
            Module module,
            Workbench workbench,
            ExplorerAuthorization authorization) {
    }

    public record ExplorerFavorite(
            String routeKey,
            ExplorerTarget target,
            String labelKey,
            Module module,
            Workbench workbench,
            ExplorerAuthorization authorization,
            int position) {
    }

    public record ExplorerRecent(
            String routeKey,
            ExplorerTarget target,
            String labelKey,
            Module module,
            Workbench workbench,
            ExplorerAuthorization authorization,
            OffsetDateTime lastVisitedAt,
            OffsetDateTime expiresAt) {
    }

    public record ExplorerActivity(
            UUID activityId,
            SourceKind sourceKind,
            Module module,
            ActivityState state,
            long sourceRevision,
            OffsetDateTime updatedAt,
            ExplorerTarget target) {
    }

    public record DegradedSource(
            Module module,
            SourceKind sourceKind,
            DegradedReason reason,
            boolean retryable) {
    }

    public record ProjectionStatus(
            ProjectionState state,
            List<DegradedSource> degradedSources) {

        public ProjectionStatus {
            degradedSources = degradedSources == null ? List.of() : List.copyOf(degradedSources);
        }
    }

    public record ExplorerPolicy(
            int maxFavorites,
            int maxRecents,
            int recentTtlDays,
            int maxInProgress,
            boolean entitlementRefilter,
            String cleanupMode) {
    }

    public record HrisExplorerResponse(
            OffsetDateTime asOf,
            long projectionRevision,
            long entitlementRevision,
            long favoritePreferenceRevision,
            List<ExplorerRoute> routes,
            List<ExplorerFavorite> favorites,
            List<ExplorerRecent> recents,
            List<ExplorerActivity> inProgress,
            String nextCursor,
            ProjectionStatus projectionStatus,
            ExplorerPolicy policy) {

        public HrisExplorerResponse {
            routes = List.copyOf(routes);
            favorites = List.copyOf(favorites);
            recents = List.copyOf(recents);
            inProgress = List.copyOf(inProgress);
        }
    }

    public record ExplorerPreferenceCommand(
            @NotNull @Size(max = 20)
            List<@NotBlank @Pattern(regexp = "^[a-z][a-z0-9.-]{2,95}$") String>
                    orderedFavoriteRouteKeys) {

        public ExplorerPreferenceCommand {
            orderedFavoriteRouteKeys = orderedFavoriteRouteKeys == null
                    ? null : List.copyOf(orderedFavoriteRouteKeys);
        }

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown Explorer preference property: " + name);
        }
    }

    public record ExplorerRecentCommand(
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9.-]{2,95}$") String routeKey) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException(
                    "Unknown Explorer recent property: " + name);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HrisExplorerReceipt(
            UUID receiptId,
            String commandType,
            String originatingAction,
            UUID subjectPrincipalPublicId,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            ReceiptStatus status,
            UUID correlationId,
            String requestDigest,
            OffsetDateTime createdAt,
            Long aggregateVersion,
            String resultRef) {
    }
}
