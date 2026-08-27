package com.dwp.services.platform.experiencepreview;

import com.dwp.services.platform.branding.TenantBrandingDtos;
import com.dwp.services.platform.branding.TenantBrandingService;
import com.dwp.services.platform.home.HomeExperienceDtos;
import com.dwp.services.platform.home.HomeExperienceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class TenantExperiencePreviewService {

    private static final List<String> EXCLUDED_DATA = List.of(
            "USER_PERSONALIZATION",
            "USER_CONTENT",
            "WORKFORCE_DATA",
            "LIVE_ANNOUNCEMENTS",
            "ASSET_LOCATIONS",
            "AUDIT_ACTOR_METADATA");

    private final TenantBrandingService brandingService;
    private final HomeExperienceService homeExperienceService;

    public TenantExperiencePreviewService(
            TenantBrandingService brandingService,
            HomeExperienceService homeExperienceService) {
        this.brandingService = brandingService;
        this.homeExperienceService = homeExperienceService;
    }

    @Transactional(readOnly = true)
    public TenantExperiencePreviewDtos.TenantExperiencePreviewResponse get(Long tenantId) {
        TenantBrandingDtos.TenantBrandingResponse branding = brandingService.get(tenantId);
        HomeExperienceDtos.HomeExperienceResponse home = homeExperienceService.get(tenantId);
        return new TenantExperiencePreviewDtos.TenantExperiencePreviewResponse(
                "tenant-experience-preview.v1",
                "TENANT_CONFIGURATION_ONLY",
                Instant.now(),
                new TenantExperiencePreviewDtos.BrandingConfiguration(
                        branding.organizationName(),
                        branding.accentColor(),
                        branding.logoUrl() != null,
                        branding.logoWidth(),
                        branding.logoHeight(),
                        branding.version()),
                new TenantExperiencePreviewDtos.HomeConfiguration(
                        home.headline(),
                        home.subheadline(),
                        home.localizedContent(),
                        home.defaultLocale(),
                        home.backgroundUrl() != null,
                        home.backgroundPosition(),
                        home.backgroundFocalX(),
                        home.backgroundFocalY(),
                        home.mobileBackgroundFocalX(),
                        home.mobileBackgroundFocalY(),
                        home.contentAlignment(),
                        home.overlayOpacity(),
                        home.backgroundWidth(),
                        home.backgroundHeight(),
                        home.launchpadConfiguration(),
                        home.compositionPolicy(),
                        home.effectiveExperienceVariant(),
                        home.version()),
                EXCLUDED_DATA);
    }
}
