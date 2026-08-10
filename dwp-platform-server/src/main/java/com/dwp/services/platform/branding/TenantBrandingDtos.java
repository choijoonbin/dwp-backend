package com.dwp.services.platform.branding;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TenantBrandingDtos {

    private TenantBrandingDtos() {
    }

    public record UpdateTenantBrandingRequest(
            @Size(max = 160) String organizationName,
            @NotNull @Min(0) Long version) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record TenantBrandingResponse(
            String organizationName,
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

    static Map<String, Object> snapshot(TenantBranding branding) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("organizationName", branding.getOrganizationName());
        value.put("logoOriginalName", branding.getLogoOriginalName());
        value.put("logoContentType", branding.getLogoContentType());
        value.put("logoSizeBytes", branding.getLogoSizeBytes());
        value.put("logoWidth", branding.getLogoWidth());
        value.put("logoHeight", branding.getLogoHeight());
        value.put("version", branding.getVersion() == null ? 0L : branding.getVersion());
        return value;
    }
}
