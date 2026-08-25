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

public final class HomeComposerDtos {
    private HomeComposerDtos() {
    }

    public record ComposerChange(
            @NotBlank @Pattern(regexp = "MOVE_WIDGET|SHOW_WIDGET|HIDE_WIDGET|SET_WIDTH|SET_DENSITY|PIN_APP|UNPIN_APP")
            @Schema(allowableValues = {
                    "MOVE_WIDGET", "SHOW_WIDGET", "HIDE_WIDGET", "SET_WIDTH",
                    "SET_DENSITY", "PIN_APP", "UNPIN_APP"})
            String operation,
            @Pattern(regexp = "[a-z][a-z0-9-]{0,39}") String widgetKey,
            @Size(max = 100) String appId,
            @Min(0) @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer beforeIndex,
            @Min(0) @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer afterIndex,
            @Size(max = 80) String value) {
    }

    public record CreateComposerProposalRequest(
            @NotNull UUID viewId,
            @NotNull @Min(0) @JsonDeserialize(using = StrictLongDeserializer.class)
            Long baseViewVersion,
            @NotNull @Size(min = 1, max = 10)
            List<@NotNull @Pattern(regexp = "[A-Z][A-Z0-9_]{1,79}") String> reasonCodes,
            @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid ComposerChange> changes) {
    }

    public record ApplyComposerProposalRequest(
            @NotNull @Min(0) @JsonDeserialize(using = StrictLongDeserializer.class)
            Long viewVersion) {
    }

    @Schema(requiredProperties = {
            "proposalId", "viewId", "state", "baseViewVersion", "reasonCodes",
            "changes", "warnings", "beforeLayout", "proposedLayout", "expiresAt"
    })
    public record ComposerProposalResponse(
            UUID proposalId,
            UUID viewId,
            @Schema(allowableValues = {
                    "PREVIEWED", "CANCELLED", "APPLIED", "UNDONE", "FAILED"})
            String state,
            Long baseViewVersion,
            List<String> reasonCodes,
            List<ComposerChange> changes,
            List<String> warnings,
            HomePreferenceDtos.HomeLayoutPayload beforeLayout,
            HomePreferenceDtos.HomeLayoutPayload proposedLayout,
            OffsetDateTime expiresAt,
            UUID appliedRevisionId,
            UUID undoneRevisionId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }
}
