package com.dwp.services.platform.branding;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TenantBrandingDtos {

    private TenantBrandingDtos() {
    }

    public record UpdateTenantBrandingRequest(
            @Size(max = 160) String organizationName,
            @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String accentColor,
            @NotNull @Min(0) Long version) {

        public UpdateTenantBrandingRequest(String organizationName, Long version) {
            this(organizationName, null, version);
        }
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record TenantBrandingResponse(
            String organizationName,
            String accentColor,
            String logoUrl,
            String logoOriginalName,
            String logoContentType,
            Long logoSizeBytes,
            Integer logoWidth,
            Integer logoHeight,
            Long version,
            LocalDateTime updatedAt,
            Long updatedBy) {
    }

    public record BrandingRevisionResponse(
            Long revisionId,
            Long sourceVersion,
            String changeType,
            String organizationName,
            String accentColor,
            String logoOriginalName,
            Integer logoWidth,
            Integer logoHeight,
            boolean current,
            OffsetDateTime createdAt,
            Long createdBy) {
    }

    @Schema(requiredProperties = {"items", "hasMore", "limit"})
    public record BrandingRevisionPage(
            List<BrandingRevisionResponse> items,
            boolean hasMore,
            int limit) {
    }

    static Map<String, Object> snapshot(TenantBranding branding) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("organizationName", branding.getOrganizationName());
        value.put("accentColor", branding.getAccentColor());
        value.put("logoOriginalName", branding.getLogoOriginalName());
        value.put("logoContentType", branding.getLogoContentType());
        value.put("logoSizeBytes", branding.getLogoSizeBytes());
        value.put("logoWidth", branding.getLogoWidth());
        value.put("logoHeight", branding.getLogoHeight());
        value.put("version", branding.getVersion() == null ? 0L : branding.getVersion());
        return value;
    }

    static Map<String, Object> revisionSnapshot(TenantBranding branding) {
        Map<String, Object> value = snapshot(branding);
        value.put("logoAssetKey", branding.getLogoAssetKey());
        value.put("logoSha256", branding.getLogoSha256());
        return value;
    }
}
