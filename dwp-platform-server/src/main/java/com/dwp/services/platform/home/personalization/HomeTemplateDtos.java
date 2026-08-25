package com.dwp.services.platform.home.personalization;

import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class HomeTemplateDtos {
    private HomeTemplateDtos() {
    }

    public record TemplateAudience(
            @NotBlank @Pattern(regexp = "ALL|ROLE")
            @Schema(allowableValues = {"ALL", "ROLE"}) String type,
            @Size(max = 20) List<@NotNull @Pattern(regexp = "[A-Z][A-Z0-9_]{1,79}") String> values) {
    }

    public record CreateHomeTemplateRequest(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{0,79}") String templateKey,
            @NotBlank @Size(max = 80) String name,
            @NotNull @Valid TemplateAudience audience,
            @NotNull @Valid HomePreferenceDtos.HomeLayoutPayload layout) {
    }

    public record UpdateHomeTemplateRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull @Valid TemplateAudience audience,
            @NotNull @Valid HomePreferenceDtos.HomeLayoutPayload layout,
            @NotNull @Min(0) @JsonDeserialize(using = StrictLongDeserializer.class) Long version) {
    }

    public record VersionRequest(
            @NotNull @Min(0) @JsonDeserialize(using = StrictLongDeserializer.class) Long version) {
    }

    public record ApplyHomeTemplateRequest(
            @NotNull UUID viewId,
            @NotNull @Min(0) @JsonDeserialize(using = StrictLongDeserializer.class)
            Long viewVersion) {
    }

    @Schema(requiredProperties = {
            "templateId", "templateKey", "name", "audience", "lifecycle",
            "schemaVersion", "layout", "version"
    })
    public record HomeTemplateResponse(
            UUID templateId,
            String templateKey,
            String name,
            TemplateAudience audience,
            @Schema(allowableValues = {"DRAFT", "PUBLISHED", "REVOKED"})
            String lifecycle,
            Integer schemaVersion,
            HomePreferenceDtos.HomeLayoutPayload layout,
            Long version,
            OffsetDateTime publishedAt,
            Long publishedBy,
            OffsetDateTime updatedAt) {
    }

    @Schema(requiredProperties = {
            "templateRevisionId", "templateId", "revisionNumber",
            "source", "snapshot", "createdAt", "createdBy"
    })
    public record HomeTemplateRevisionResponse(
            UUID templateRevisionId,
            UUID templateId,
            Long revisionNumber,
            @Schema(allowableValues = {"CREATE", "UPDATE", "PUBLISH", "REVOKE"})
            String source,
            HomeTemplateSnapshot snapshot,
            OffsetDateTime createdAt,
            Long createdBy) {
    }

    @Schema(requiredProperties = {
            "name", "audience", "lifecycle", "schemaVersion", "layout", "version"
    })
    public record HomeTemplateSnapshot(
            String name,
            TemplateAudience audience,
            @Schema(allowableValues = {"DRAFT", "PUBLISHED", "REVOKED"})
            String lifecycle,
            Integer schemaVersion,
            HomePreferenceDtos.HomeLayoutPayload layout,
            Long version,
            OffsetDateTime publishedAt,
            Long publishedBy) {
    }
}
