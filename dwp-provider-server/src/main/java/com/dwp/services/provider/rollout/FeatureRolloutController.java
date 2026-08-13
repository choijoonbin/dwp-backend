package com.dwp.services.provider.rollout;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/feature-rollouts")
public class FeatureRolloutController {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final FeatureRolloutService service;

    public FeatureRolloutController(FeatureRolloutService service) {
        this.service = service;
    }

    @GetMapping("/flags")
    public ApiResponse<List<FeatureRolloutDtos.FeatureFlag>> flags() {
        return ApiResponse.success(service.flags());
    }

    @PostMapping("/flags")
    public ApiResponse<FeatureRolloutDtos.FeatureFlag> createFlag(
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody FeatureRolloutDtos.CreateFeatureFlagRequest request) {
        return ApiResponse.success(service.createFlag(request, correlationId));
    }

    @GetMapping
    public ApiResponse<List<FeatureRolloutDtos.Rollout>> rollouts(
            @RequestParam(required = false) String featureKey) {
        return ApiResponse.success(service.rollouts(featureKey));
    }

    @GetMapping("/{rolloutId}")
    public ApiResponse<FeatureRolloutDtos.Rollout> rollout(@PathVariable UUID rolloutId) {
        return ApiResponse.success(service.rollout(rolloutId));
    }

    @PostMapping("/flags/{featureKey}/revisions")
    public ApiResponse<FeatureRolloutDtos.Rollout> createRollout(
            @PathVariable String featureKey,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody FeatureRolloutDtos.CreateRolloutRequest request) {
        return ApiResponse.success(service.createRollout(featureKey, request, correlationId));
    }

    @PostMapping("/{rolloutId}/submit")
    public ApiResponse<FeatureRolloutDtos.Rollout> submit(
            @PathVariable UUID rolloutId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody FeatureRolloutDtos.VersionedReasonRequest request) {
        return ApiResponse.success(service.submit(rolloutId, request, correlationId));
    }

    @PostMapping("/{rolloutId}/approval")
    public ApiResponse<FeatureRolloutDtos.Rollout> decide(
            @PathVariable UUID rolloutId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody FeatureRolloutDtos.ApprovalDecisionRequest request) {
        return ApiResponse.success(service.decide(rolloutId, request, correlationId));
    }

    @PostMapping("/{rolloutId}/activate")
    public ApiResponse<FeatureRolloutDtos.Rollout> activate(
            @PathVariable UUID rolloutId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody FeatureRolloutDtos.VersionedReasonRequest request) {
        return ApiResponse.success(service.activate(rolloutId, request, correlationId));
    }

    @PostMapping("/{rolloutId}/pause")
    public ApiResponse<FeatureRolloutDtos.Rollout> pause(
            @PathVariable UUID rolloutId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody FeatureRolloutDtos.VersionedReasonRequest request) {
        return ApiResponse.success(service.pause(rolloutId, request, correlationId));
    }

    @PostMapping("/{rolloutId}/resume")
    public ApiResponse<FeatureRolloutDtos.Rollout> resume(
            @PathVariable UUID rolloutId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody FeatureRolloutDtos.VersionedReasonRequest request) {
        return ApiResponse.success(service.resume(rolloutId, request, correlationId));
    }

    @PostMapping("/{rolloutId}/advance")
    public ApiResponse<FeatureRolloutDtos.Rollout> advance(
            @PathVariable UUID rolloutId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody FeatureRolloutDtos.AdvanceRequest request) {
        return ApiResponse.success(service.advance(rolloutId, request, correlationId));
    }

    @PostMapping("/{rolloutId}/rollback")
    public ApiResponse<FeatureRolloutDtos.Rollout> rollback(
            @PathVariable UUID rolloutId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody FeatureRolloutDtos.VersionedReasonRequest request) {
        return ApiResponse.success(service.rollback(rolloutId, request, correlationId));
    }

    @GetMapping("/flags/{featureKey}/evaluate")
    public ApiResponse<FeatureRolloutDtos.Evaluation> evaluate(
            @PathVariable String featureKey,
            @RequestParam UUID tenantId) {
        return ApiResponse.success(service.evaluate(featureKey, tenantId));
    }
}
