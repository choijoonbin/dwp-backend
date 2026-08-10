package com.dwp.services.platform.home;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HomeExperienceDtos {

    private HomeExperienceDtos() {
    }

    public record UpdateHomeExperienceRequest(
            @Size(max = 160) String headline,
            @Size(max = 500) String subheadline,
            @NotNull @Pattern(regexp = "LEFT|CENTER|RIGHT") String backgroundPosition,
            @NotNull @Min(0) @Max(70) Integer overlayOpacity,
            @NotNull @Min(0) Long version) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record HomeExperienceResponse(
            String headline,
            String subheadline,
            String backgroundPosition,
            Integer overlayOpacity,
            String backgroundUrl,
            String backgroundOriginalName,
            String backgroundContentType,
            Long backgroundSizeBytes,
            Integer backgroundWidth,
            Integer backgroundHeight,
            Long version,
            LocalDateTime updatedAt,
            Long updatedBy) {
    }

    static Map<String, Object> snapshot(HomeExperience experience) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("headline", experience.getHeadline());
        value.put("subheadline", experience.getSubheadline());
        value.put("backgroundPosition", experience.getBackgroundPosition());
        value.put("overlayOpacity", experience.getOverlayOpacity());
        value.put("backgroundOriginalName", experience.getBackgroundOriginalName());
        value.put("backgroundContentType", experience.getBackgroundContentType());
        value.put("backgroundSizeBytes", experience.getBackgroundSizeBytes());
        value.put("backgroundWidth", experience.getBackgroundWidth());
        value.put("backgroundHeight", experience.getBackgroundHeight());
        value.put("version", experience.getVersion() == null ? 0L : experience.getVersion());
        return value;
    }
}
