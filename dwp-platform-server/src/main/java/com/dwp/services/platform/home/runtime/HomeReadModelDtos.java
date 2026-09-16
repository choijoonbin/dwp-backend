package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.HomeExperienceDtos;
import com.dwp.services.platform.home.personalization.HomeViewDtos;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class HomeReadModelDtos {

    public static final int SCHEMA_VERSION = 3;

    private HomeReadModelDtos() {
    }

    public record HomeReadModel(
            int schemaVersion,
            String mode,
            EffectiveView view,
            HomeShell shell,
            List<AppGroup> appDock,
            List<Widget> widgets,
            RuntimeDecision runtime,
            OffsetDateTime generatedAt,
            OffsetDateTime expiresAt,
            boolean partial,
            List<String> unavailableSources,
            String changeVersion,
            String registryMode) {

        public HomeReadModel(
                int schemaVersion,
                String mode,
                EffectiveView view,
                HomeShell shell,
                List<AppGroup> appDock,
                List<Widget> widgets,
                OffsetDateTime generatedAt,
                OffsetDateTime expiresAt,
                boolean partial,
                List<String> unavailableSources,
                String changeVersion,
                String registryMode) {
            this(schemaVersion, mode, view, shell, appDock, widgets,
                    new RuntimeDecision(
                            "SHADOW_COMPARE", mode, "CONTROL", "legacy-shadow",
                            false, false, expiresAt),
                    generatedAt, expiresAt, partial, unavailableSources, changeVersion,
                    registryMode);
        }
    }

    /** Bounded browser-visible rollout decision. Internal provider/definition allowlists stay server-side. */
    public record RuntimeDecision(
            String state,
            String homeMode,
            String rolloutRing,
            String rolloutRevision,
            boolean commandsEnabled,
            boolean registryAuthoritative,
            OffsetDateTime expiresAt) {
    }

    public record EffectiveView(
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID viewId,
            long revision,
            String source,
            String mode,
            String deviceClass,
            HomePreferenceDtos.HomeLayoutPayload composition,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            HomeViewDtos.DeviceLayoutOverlay deviceOverlay) {
    }

    public record HomeShell(
            String headline,
            String subheadline,
            String contentAlignment,
            String density,
            String backgroundAssetRoute,
            List<Announcement> announcements) {
    }

    public record Announcement(
            String id,
            String kind,
            String title,
            OffsetDateTime dueAt,
            String sourceRoute) {
    }

    public record AppGroup(
            String groupKey,
            String label,
            List<AppEntry> apps) {
    }

    public record AppEntry(
            String appKey,
            String label,
            String iconKey,
            String sourceRoute,
            BadgeState badgeState,
            @JsonInclude(JsonInclude.Include.NON_NULL) Badge badge) {
    }

    public enum BadgeState {
        NOT_REQUESTED,
        AVAILABLE,
        UNAVAILABLE,
        FORBIDDEN
    }

    public record Badge(int total, int urgent, String version) {
    }

    public record Widget(
            UUID instanceId,
            String definitionKey,
            String definitionVersion,
            String definitionManifestHash,
            String rendererBindingRevision,
            String rendererKey,
            HomeWidgetProviderContract.State state,
            SourceState source,
            Map<String, Object> payload,
            List<HomeWidgetProviderContract.Action> actions,
            List<String> redactions,
            Governance governance) {
    }

    public record SourceState(
            String sourceKey,
            OffsetDateTime generatedAt,
            OffsetDateTime expiresAt,
            OffsetDateTime lastSuccessAt,
            String reasonCode,
            boolean retryable,
            String resultVersion) {
    }

    public record Governance(
            String owner,
            String sourceAppResourceKey,
            List<String> requiredAuthorities,
            String classification,
            String retention,
            String sourceRoute) {
    }

    public record ReadResult(
            HomeReadModel model,
            String etag,
            HomeRuntimeRolloutDecision decision) {

        public ReadResult(HomeReadModel model, String etag) {
            this(model, etag, new HomeRuntimeRolloutDecision(
                    HomeRuntimeRolloutDecision.State.valueOf(model.runtime().state()),
                    model.runtime().homeMode(),
                    HomeRuntimeRolloutDecision.Ring.valueOf(model.runtime().rolloutRing()),
                    model.runtime().rolloutRevision(),
                    model.runtime().registryAuthoritative(),
                    java.util.Set.of(),
                    java.util.Set.of(),
                    java.util.Set.of(),
                    model.runtime().expiresAt()));
        }
    }

    public record CommandRequest(
            @NotNull UUID instanceId,
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9.-]{1,79}") String actionId,
            @NotBlank @Size(max = 160) String expectedResultVersion,
            @Size(max = 40) Map<String, Object> parameters) {

        public CommandRequest {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }

    public record CommandReceipt(
            UUID receiptId,
            UUID commandId,
            String commandKey,
            String status,
            String sourceRoute,
            OffsetDateTime acceptedAt,
            String resultVersion) {
    }

    static HomeShell shell(HomeExperienceDtos.HomeExperienceResponse experience) {
        return new HomeShell(
                experience.headline(),
                experience.subheadline(),
                experience.contentAlignment(),
                "COMFORTABLE",
                experience.backgroundUrl(),
                List.of());
    }
}
