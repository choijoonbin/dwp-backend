package com.dwp.services.provider.rollout;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/provider/v1/feature-rollouts/application-receipts")
public class FeatureRolloutApplicationReceiptController {

    private final FeatureRolloutApplicationReceiptService service;

    public FeatureRolloutApplicationReceiptController(
            FeatureRolloutApplicationReceiptService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(operationId = "acknowledgeProductSurfaceFeatureRolloutApplicationInternal")
    public ApiResponse<FeatureRolloutDtos.ApplicationReceipt> acknowledge(
            @Valid @RequestBody FeatureRolloutDtos.ApplicationReceiptRequest request) {
        return ApiResponse.success(service.acknowledge(request));
    }
}
