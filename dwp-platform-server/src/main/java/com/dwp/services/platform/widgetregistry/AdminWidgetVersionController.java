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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/admin/widget-definition-versions")
public class AdminWidgetVersionController {
    private static final String ACTOR = "X-DWP-User-ID";
    private static final String COMMAND = "Idempotency-Key";
    private static final String CORRELATION = "X-Correlation-ID";
    private final WidgetRegistryDefinitionService definitions;
    private final WidgetRegistryReleaseService releases;

    public AdminWidgetVersionController(
            WidgetRegistryDefinitionService definitions,
            WidgetRegistryReleaseService releases) {
        this.definitions = definitions;
        this.releases = releases;
    }

    @GetMapping("/{versionId}")
    public ApiResponse<WidgetRegistryDtos.VersionResponse> get(@PathVariable UUID versionId) {
        return ApiResponse.success(definitions.getVersion(versionId));
    }

    @PutMapping("/{versionId}")
    public ApiResponse<WidgetRegistryDtos.VersionResponse> update(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID versionId,
            @Valid @RequestBody WidgetRegistryDtos.VersionUpdateRequest request) {
        return ApiResponse.success(definitions.updateVersion(
                actorId, commandId, correlationId, versionId, request));
    }

    @GetMapping("/{versionId}/impact")
    public ApiResponse<WidgetRegistryDtos.ImpactResponse> impact(
            @PathVariable UUID versionId,
            @RequestParam @Pattern(regexp = "PUBLISH|BLOCK|QUARANTINE|REVOKE|PROMOTE|ROLLBACK")
            String operation) {
        return ApiResponse.success(releases.impact(versionId, operation));
    }

    @PostMapping("/{versionId}/validate")
    public ApiResponse<WidgetRegistryDtos.ValidationResponse> validate(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID versionId,
            @Valid @RequestBody WidgetRegistryDtos.ValidateRequest request) {
        return ApiResponse.success(definitions.validate(
                actorId, commandId, correlationId, versionId, request));
    }

    @PostMapping("/{versionId}/submit")
    public ApiResponse<WidgetRegistryDtos.VersionResponse> submit(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID versionId,
            @Valid @RequestBody WidgetRegistryDtos.TransitionRequest request) {
        return ApiResponse.success(definitions.submit(
                actorId, commandId, correlationId, versionId, request));
    }

    @PostMapping("/{versionId}/decision")
    public ApiResponse<WidgetRegistryDtos.VersionResponse> decision(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID versionId,
            @Valid @RequestBody WidgetRegistryDtos.ReviewDecisionRequest request) {
        return ApiResponse.success(definitions.decide(
                actorId, commandId, correlationId, versionId, request));
    }

    @PostMapping("/{versionId}/rework")
    public ApiResponse<WidgetRegistryDtos.VersionResponse> rework(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID versionId,
            @Valid @RequestBody WidgetRegistryDtos.TransitionRequest request) {
        return ApiResponse.success(definitions.rework(
                actorId, commandId, correlationId, versionId, request));
    }

    @PostMapping("/{versionId}/evidence")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WidgetRegistryDtos.EvidenceResponse> evidence(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID versionId,
            @Valid @RequestBody WidgetRegistryDtos.EvidenceCreateRequest request) {
        return ApiResponse.success(definitions.recordEvidence(
                actorId, commandId, correlationId, versionId, request));
    }

    @GetMapping("/{versionId}/evidence")
    public ApiResponse<WidgetRegistryDtos.EvidencePage> evidence(
            @PathVariable UUID versionId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ApiResponse.success(definitions.evidence(versionId, page, size));
    }

    @GetMapping("/{versionId}/evidence/{evidenceId}")
    public ApiResponse<WidgetRegistryDtos.EvidenceResponse> evidence(
            @PathVariable UUID versionId, @PathVariable UUID evidenceId) {
        return ApiResponse.success(definitions.evidence(versionId, evidenceId));
    }

    @PostMapping("/{versionId}/publish")
    public ApiResponse<WidgetRegistryDtos.VersionResponse> publish(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID versionId,
            @Valid @RequestBody WidgetRegistryDtos.PublishRequest request) {
        return ApiResponse.success(releases.publish(
                actorId, commandId, correlationId, versionId, request));
    }

    @PostMapping("/{versionId}/block")
    public ApiResponse<WidgetRegistryDtos.VersionResponse> block(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID versionId,
            @Valid @RequestBody WidgetRegistryDtos.SafetyTransitionRequest request) {
        return ApiResponse.success(releases.block(
                actorId, commandId, correlationId, versionId, request));
    }

    @PostMapping("/{versionId}/deprecate")
    public ApiResponse<WidgetRegistryDtos.VersionResponse> deprecate(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID versionId,
            @Valid @RequestBody WidgetRegistryDtos.DeprecateRequest request) {
        return ApiResponse.success(releases.deprecate(
                actorId, commandId, correlationId, versionId, request));
    }

    @PostMapping("/{versionId}/quarantine")
    public ApiResponse<WidgetRegistryDtos.VersionResponse> quarantine(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID versionId,
            @Valid @RequestBody WidgetRegistryDtos.SafetyTransitionRequest request) {
        return ApiResponse.success(releases.quarantine(
                actorId, commandId, correlationId, versionId, request));
    }

    @PostMapping("/{versionId}/revoke")
    public ApiResponse<WidgetRegistryDtos.VersionResponse> revoke(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID versionId,
            @Valid @RequestBody WidgetRegistryDtos.SafetyTransitionRequest request) {
        return ApiResponse.success(releases.revoke(
                actorId, commandId, correlationId, versionId, request));
    }
}
