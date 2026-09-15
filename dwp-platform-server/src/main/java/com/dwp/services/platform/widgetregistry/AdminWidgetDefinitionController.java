package com.dwp.services.platform.widgetregistry;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/admin/widget-definitions")
public class AdminWidgetDefinitionController {
    private static final String ACTOR = "X-DWP-User-ID";
    private static final String COMMAND = "Idempotency-Key";
    private static final String CORRELATION = "X-Correlation-ID";
    private final WidgetRegistryDefinitionService definitions;
    private final WidgetRegistryReleaseService releases;

    public AdminWidgetDefinitionController(
            WidgetRegistryDefinitionService definitions,
            WidgetRegistryReleaseService releases) {
        this.definitions = definitions;
        this.releases = releases;
    }

    @GetMapping
    public ApiResponse<WidgetRegistryDtos.DefinitionPage> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|RETIRED") String definitionState) {
        return ApiResponse.success(definitions.list(page, size, definitionState));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WidgetRegistryDtos.DefinitionResponse> create(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody WidgetRegistryDtos.DefinitionCreateRequest request) {
        return ApiResponse.success(definitions.create(actorId, commandId, correlationId, request));
    }

    @GetMapping("/{definitionId}")
    public ApiResponse<WidgetRegistryDtos.DefinitionResponse> get(@PathVariable UUID definitionId) {
        return ApiResponse.success(definitions.get(definitionId));
    }

    @GetMapping("/{definitionId}/versions")
    public ApiResponse<WidgetRegistryDtos.VersionPage> versions(
            @PathVariable UUID definitionId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ApiResponse.success(definitions.versions(definitionId, page, size));
    }

    @PostMapping("/{definitionId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WidgetRegistryDtos.VersionResponse> createVersion(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID definitionId,
            @Valid @RequestBody WidgetRegistryDtos.VersionCreateRequest request) {
        return ApiResponse.success(definitions.createVersion(
                actorId, commandId, correlationId, definitionId, request));
    }

    @GetMapping("/{definitionId}/retirement-impact")
    public ApiResponse<WidgetRegistryDtos.ImpactResponse> retirementImpact(
            @PathVariable UUID definitionId,
            @RequestParam(required = false) UUID replacementDefinitionId) {
        return ApiResponse.success(definitions.retirementImpact(definitionId, replacementDefinitionId));
    }

    @PostMapping("/{definitionId}/retire")
    public ApiResponse<WidgetRegistryDtos.DefinitionResponse> retire(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID definitionId,
            @Valid @RequestBody WidgetRegistryDtos.DefinitionRetireRequest request) {
        return ApiResponse.success(definitions.retire(
                actorId, commandId, correlationId, definitionId, request));
    }

    @GetMapping("/{definitionId}/channels/{channel}")
    public ApiResponse<WidgetRegistryDtos.ReleaseChannelResponse> channel(
            @PathVariable UUID definitionId,
            @PathVariable @Pattern(regexp = "STABLE|PREVIEW") String channel) {
        return ApiResponse.success(releases.channel(definitionId, channel));
    }

    @GetMapping("/{definitionId}/channels/{channel}/impact")
    public ApiResponse<WidgetRegistryDtos.ImpactResponse> channelImpact(
            @PathVariable UUID definitionId,
            @PathVariable @Pattern(regexp = "STABLE|PREVIEW") String channel,
            @RequestParam @Pattern(regexp = "PROMOTE|ROLLBACK") String operation,
            @RequestParam UUID targetVersionId) {
        return ApiResponse.success(releases.channelImpact(
                definitionId, channel, operation, targetVersionId));
    }

    @PostMapping("/{definitionId}/channels/{channel}/promote")
    public ApiResponse<WidgetRegistryDtos.ReleaseChannelResponse> promote(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID definitionId,
            @PathVariable @Pattern(regexp = "STABLE|PREVIEW") String channel,
            @Valid @RequestBody WidgetRegistryDtos.ChannelTransitionRequest request) {
        return ApiResponse.success(releases.promote(
                actorId, commandId, correlationId, definitionId, channel, request));
    }

    @PostMapping("/{definitionId}/channels/{channel}/rollback")
    public ApiResponse<WidgetRegistryDtos.ReleaseChannelResponse> rollback(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID definitionId,
            @PathVariable @Pattern(regexp = "STABLE|PREVIEW") String channel,
            @Valid @RequestBody WidgetRegistryDtos.ChannelRollbackRequest request) {
        return ApiResponse.success(releases.rollback(
                actorId, commandId, correlationId, definitionId, channel, request));
    }
}
