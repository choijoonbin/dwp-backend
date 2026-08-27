package com.dwp.services.platform.home;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

public final class HomeExperienceDtos {

    private HomeExperienceDtos() {
    }

    public record UpdateHomeExperienceRequest(
            @Size(max = 160) String headline,
            @Size(max = 500) String subheadline,
            Map<String, LocalizedCopy> localizedContent,
            @Pattern(regexp = "^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$") String defaultLocale,
            @NotNull @Pattern(regexp = "LEFT|CENTER|RIGHT") String backgroundPosition,
            @Min(0) @Max(100) Integer backgroundFocalX,
            @Min(0) @Max(100) Integer backgroundFocalY,
            @Min(0) @Max(100) Integer mobileBackgroundFocalX,
            @Min(0) @Max(100) Integer mobileBackgroundFocalY,
            @Pattern(regexp = "LEFT|CENTER|RIGHT") String contentAlignment,
            @NotNull @Min(0) @Max(70) Integer overlayOpacity,
            @NotNull @Min(0) Long version) {

        public UpdateHomeExperienceRequest(
                String headline,
                String subheadline,
                String backgroundPosition,
                Integer overlayOpacity,
                Long version) {
            this(
                    headline, subheadline, null, null, backgroundPosition,
                    null, null, null, null, null, overlayOpacity, version);
        }

        public UpdateHomeExperienceRequest(
                String headline,
                String subheadline,
                Map<String, LocalizedCopy> localizedContent,
                String defaultLocale,
                String backgroundPosition,
                Integer overlayOpacity,
                Long version) {
            this(
                    headline, subheadline, localizedContent, defaultLocale, backgroundPosition,
                    null, null, null, null, null, overlayOpacity, version);
        }
    }

    public record LocalizedCopy(
            @Size(max = 160) String headline,
            @Size(max = 500) String subheadline) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record UpdateLaunchpadConfigurationRequest(
            @NotNull @Valid HomeLaunchpadConfiguration configuration,
            @NotNull @Min(0) Long version) {
    }

    public record UpdateHomeCompositionPolicyRequest(
            @NotNull @Valid HomeCompositionPolicy policy,
            @NotNull @Min(0) Long version) {
    }

    @Schema(requiredProperties = {
            "schemaVersion", "experienceVariant", "personalCustomizationEnabled",
            "governedZones"
    })
    public record HomeCompositionPolicy(
            Integer schemaVersion,
            @Schema(allowableValues = {"CLASSIC", "FLOW_V1"})
            String experienceVariant,
            Boolean personalCustomizationEnabled,
            List<GovernedHomeZone> governedZones) {

        public HomeCompositionPolicy(
                Integer schemaVersion,
                Boolean personalCustomizationEnabled,
                List<GovernedHomeZone> governedZones) {
            this(schemaVersion, null, personalCustomizationEnabled, governedZones);
        }
    }

    public record GovernedHomeZone(
            String zoneKey,
            String placement,
            Boolean visible,
            String size,
            String height,
            Integer sortOrder) {
    }

    public record HomeLaunchpadConfiguration(
            Integer schemaVersion,
            List<HomeLaunchpadGroup> groups,
            List<HomeAppPlacement> placements) {
    }

    public record HomeLaunchpadGroup(
            String groupKey,
            Map<String, String> labels,
            Map<String, String> descriptions,
            Integer sortOrder,
            Boolean enabled) {
    }

    public record HomeAppPlacement(
            String resourceKey,
            String groupKey,
            Integer sortOrder) {
    }

    @Schema(requiredProperties = {
            "backgroundPosition", "backgroundFocalX", "backgroundFocalY",
            "mobileBackgroundFocalX", "mobileBackgroundFocalY", "contentAlignment",
            "overlayOpacity", "launchpadConfiguration",
            "compositionPolicy", "effectiveExperienceVariant",
            "advancedPersonalizationEnabled", "composerEnabled",
            "homePreferenceStore", "version"
    })
    public record HomeExperienceResponse(
            String headline,
            String subheadline,
            Map<String, LocalizedCopy> localizedContent,
            String defaultLocale,
            String backgroundPosition,
            Integer backgroundFocalX,
            Integer backgroundFocalY,
            Integer mobileBackgroundFocalX,
            Integer mobileBackgroundFocalY,
            @Schema(allowableValues = {"LEFT", "CENTER", "RIGHT"}) String contentAlignment,
            Integer overlayOpacity,
            String backgroundUrl,
            String backgroundOriginalName,
            String backgroundContentType,
            Long backgroundSizeBytes,
            Integer backgroundWidth,
            Integer backgroundHeight,
            HomeLaunchpadConfiguration launchpadConfiguration,
            HomeCompositionPolicy compositionPolicy,
            @Schema(allowableValues = {"CLASSIC", "FLOW_V1"})
            String effectiveExperienceVariant,
            Boolean advancedPersonalizationEnabled,
            Boolean composerEnabled,
            @Schema(allowableValues = {"LEGACY", "VIEWS"}) String homePreferenceStore,
            Long version,
            OffsetDateTime updatedAt,
            Long updatedBy) {

        public HomeExperienceResponse(
                String headline,
                String subheadline,
                Map<String, LocalizedCopy> localizedContent,
                String defaultLocale,
                String backgroundPosition,
                Integer overlayOpacity,
                String backgroundUrl,
                String backgroundOriginalName,
                String backgroundContentType,
                Long backgroundSizeBytes,
                Integer backgroundWidth,
                Integer backgroundHeight,
                HomeLaunchpadConfiguration launchpadConfiguration,
                HomeCompositionPolicy compositionPolicy,
                String effectiveExperienceVariant,
                Boolean advancedPersonalizationEnabled,
                Boolean composerEnabled,
                String homePreferenceStore,
                Long version,
                OffsetDateTime updatedAt,
                Long updatedBy) {
            this(
                    headline, subheadline, localizedContent, defaultLocale, backgroundPosition,
                    50, 50, 50, 50, "LEFT", overlayOpacity, backgroundUrl,
                    backgroundOriginalName, backgroundContentType, backgroundSizeBytes,
                    backgroundWidth, backgroundHeight, launchpadConfiguration,
                    compositionPolicy, effectiveExperienceVariant,
                    advancedPersonalizationEnabled, composerEnabled, homePreferenceStore,
                    version, updatedAt, updatedBy);
        }
    }

    public record HomeExperienceRevisionResponse(
            Long revisionId,
            Long sourceVersion,
            String changeType,
            String headline,
            String backgroundOriginalName,
            Integer backgroundWidth,
            Integer backgroundHeight,
            int localeCount,
            @Schema(
                    description = "Aggregate scopes that a rollback to this revision replaces.",
                    allowableValues = {
                            "PRESENTATION", "BACKGROUND_ASSET", "LAUNCHPAD", "COMPOSITION"
                    })
            List<String> affectedScopes,
            boolean current,
            OffsetDateTime createdAt,
            Long createdBy) {
    }

    static Map<String, Object> snapshot(HomeExperience experience) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("headline", experience.getHeadline());
        value.put("subheadline", experience.getSubheadline());
        value.put("localizedContent", experience.getLocalizedContent());
        value.put("defaultLocale", experience.getDefaultLocale());
        value.put("backgroundPosition", experience.getBackgroundPosition());
        value.put("backgroundFocalX", experience.getBackgroundFocalX());
        value.put("backgroundFocalY", experience.getBackgroundFocalY());
        value.put("mobileBackgroundFocalX", experience.getMobileBackgroundFocalX());
        value.put("mobileBackgroundFocalY", experience.getMobileBackgroundFocalY());
        value.put("contentAlignment", experience.getContentAlignment());
        value.put("overlayOpacity", experience.getOverlayOpacity());
        value.put("launchpadConfiguration", experience.getLaunchpadConfiguration());
        value.put("compositionPolicy", experience.getCompositionPolicy());
        value.put("backgroundOriginalName", experience.getBackgroundOriginalName());
        value.put("backgroundContentType", experience.getBackgroundContentType());
        value.put("backgroundSizeBytes", experience.getBackgroundSizeBytes());
        value.put("backgroundWidth", experience.getBackgroundWidth());
        value.put("backgroundHeight", experience.getBackgroundHeight());
        value.put("version", experience.getVersion() == null ? 0L : experience.getVersion());
        return value;
    }

    static Map<String, Object> revisionSnapshot(HomeExperience experience) {
        Map<String, Object> value = snapshot(experience);
        value.put("backgroundAssetKey", experience.getBackgroundAssetKey());
        value.put("backgroundSha256", experience.getBackgroundSha256());
        return value;
    }
}
