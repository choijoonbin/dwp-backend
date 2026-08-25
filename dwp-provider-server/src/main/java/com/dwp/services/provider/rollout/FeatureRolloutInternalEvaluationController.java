package com.dwp.services.provider.rollout;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/provider/v1/feature-rollouts")
public class FeatureRolloutInternalEvaluationController {

    private final FeatureRolloutInternalEvaluationService service;

    public FeatureRolloutInternalEvaluationController(
            FeatureRolloutInternalEvaluationService service) {
        this.service = service;
    }

    @PostMapping("/evaluate")
    @Operation(operationId = "evaluateProductSurfaceFeatureRolloutInternal")
    public ApiResponse<FeatureRolloutDtos.InternalEvaluation> evaluate(
            @Valid @RequestBody FeatureRolloutDtos.InternalEvaluationRequest request) {
        return ApiResponse.success(service.evaluate(request));
    }
}
