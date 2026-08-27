package com.dwp.services.platform.experiencepreview;

import com.dwp.services.platform.home.HomeExperienceDtos;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class TenantExperiencePreviewDtos {

    private TenantExperiencePreviewDtos() {
    }

    public record TenantExperiencePreviewResponse(
            String contractVersion,
            String previewMode,
            Instant generatedAt,
            BrandingConfiguration branding,
            HomeConfiguration home,
            List<String> excludedData) {
    }

    public record BrandingConfiguration(
            String organizationName,
            String accentColor,
            boolean logoConfigured,
            Integer logoWidth,
            Integer logoHeight,
            Long version) {
    }

    public record HomeConfiguration(
            String headline,
            String subheadline,
            Map<String, HomeExperienceDtos.LocalizedCopy> localizedContent,
            String defaultLocale,
            boolean backgroundConfigured,
            String backgroundPosition,
            Integer backgroundFocalX,
            Integer backgroundFocalY,
            Integer mobileBackgroundFocalX,
            Integer mobileBackgroundFocalY,
            String contentAlignment,
            Integer overlayOpacity,
            Integer backgroundWidth,
            Integer backgroundHeight,
            HomeExperienceDtos.HomeLaunchpadConfiguration launchpadConfiguration,
            HomeExperienceDtos.HomeCompositionPolicy compositionPolicy,
            String effectiveExperienceVariant,
            Long version) {

        public HomeConfiguration(
                String headline,
                String subheadline,
                Map<String, HomeExperienceDtos.LocalizedCopy> localizedContent,
                String defaultLocale,
                boolean backgroundConfigured,
                String backgroundPosition,
                Integer overlayOpacity,
                Integer backgroundWidth,
                Integer backgroundHeight,
                HomeExperienceDtos.HomeLaunchpadConfiguration launchpadConfiguration,
                HomeExperienceDtos.HomeCompositionPolicy compositionPolicy,
                String effectiveExperienceVariant,
                Long version) {
            this(
                    headline, subheadline, localizedContent, defaultLocale,
                    backgroundConfigured, backgroundPosition,
                    50, 50, 50, 50, "LEFT", overlayOpacity,
                    backgroundWidth, backgroundHeight, launchpadConfiguration,
                    compositionPolicy, effectiveExperienceVariant, version);
        }
    }
}
