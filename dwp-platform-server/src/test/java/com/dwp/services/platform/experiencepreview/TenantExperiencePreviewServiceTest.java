package com.dwp.services.platform.experiencepreview;

import com.dwp.services.platform.branding.TenantBrandingDtos;
import com.dwp.services.platform.branding.TenantBrandingService;
import com.dwp.services.platform.home.HomeExperienceDtos;
import com.dwp.services.platform.home.HomeExperienceService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantExperiencePreviewServiceTest {

    @Test
    void returnsOnlyTenantConfigurationAndRedactsAssetAndActorMetadata() {
        TenantBrandingService brandingService = mock(TenantBrandingService.class);
        HomeExperienceService homeService = mock(HomeExperienceService.class);
        when(brandingService.get(42L)).thenReturn(new TenantBrandingDtos.TenantBrandingResponse(
                "Acme", "#123456", "/private/logo", "secret-logo.png", "image/png",
                8192L, 240, 80, 7L, LocalDateTime.now(), 900L));
        when(homeService.get(42L)).thenReturn(new HomeExperienceDtos.HomeExperienceResponse(
                "Welcome", "Acme workspace", Map.of(), "ko-KR", "CENTER", 30,
                "/private/background", "sensitive-file-name.png", "image/png", 1024L,
                1920, 1080,
                new HomeExperienceDtos.HomeLaunchpadConfiguration(1, List.of(), List.of()),
                new HomeExperienceDtos.HomeCompositionPolicy(1, "CLASSIC", false, List.of()),
                "CLASSIC", false, false, "LEGACY", 9L, OffsetDateTime.now(), 901L));
        TenantExperiencePreviewService service =
                new TenantExperiencePreviewService(brandingService, homeService);

        TenantExperiencePreviewDtos.TenantExperiencePreviewResponse result = service.get(42L);

        assertThat(result.previewMode()).isEqualTo("TENANT_CONFIGURATION_ONLY");
        assertThat(result.generatedAt()).isNotNull();
        assertThat(result.branding().logoConfigured()).isTrue();
        assertThat(result.home().backgroundConfigured()).isTrue();
        assertThat(result.excludedData()).contains(
                "USER_PERSONALIZATION", "WORKFORCE_DATA", "ASSET_LOCATIONS");
        assertThat(result.toString())
                .doesNotContain("/private/", "secret-logo.png", "sensitive-file-name.png");
    }
}
