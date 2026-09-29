package com.dwp.services.people.hr.performance;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/hris/performance")
@ConditionalOnProperty(
        name = "dwp.hris.performance.wave1.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class PerformanceCycleController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final PerformanceCycleService service;

    public PerformanceCycleController(PerformanceCycleService service) {
        this.service = service;
    }

    @GetMapping("/cycles")
    public ApiResponse<PerformanceCycleDtos.CycleCollection> cycles() {
        return ApiResponse.success(service.cycles());
    }

    @GetMapping("/cycles/{cycleId}")
    public ApiResponse<PerformanceCycleDtos.CycleDetail> cycle(@PathVariable UUID cycleId) {
        return ApiResponse.success(service.cycle(cycleId));
    }

    @PostMapping("/cycles")
    public ApiResponse<PerformanceCycleDtos.CycleCommandResult> create(
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody PerformanceCycleDtos.CreateCycleRequest request) {
        return ApiResponse.success(service.create(request, idempotencyKey, correlationId));
    }

    @PatchMapping("/cycles/{cycleId}")
    public ApiResponse<PerformanceCycleDtos.CycleCommandResult> update(
            @PathVariable UUID cycleId,
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody PerformanceCycleDtos.UpdateCycleRequest request) {
        return ApiResponse.success(
                service.update(cycleId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/cycles/{cycleId}/validate")
    public ApiResponse<PerformanceCycleDtos.CycleCommandResult> validate(
            @PathVariable UUID cycleId,
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody PerformanceCycleDtos.ValidateCycleRequest request) {
        return ApiResponse.success(
                service.validate(cycleId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/cycles/{cycleId}/population-previews")
    public ApiResponse<PerformanceCycleDtos.PreviewCommandResult> preview(
            @PathVariable UUID cycleId,
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody PerformanceCycleDtos.PreviewPopulationRequest request) {
        return ApiResponse.success(
                service.preview(cycleId, request, idempotencyKey, correlationId));
    }

    @PostMapping("/cycles/{cycleId}/publish")
    public ApiResponse<PerformanceCycleDtos.CycleCommandResult> publish(
            @PathVariable UUID cycleId,
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody PerformanceCycleDtos.PublishCycleRequest request) {
        return ApiResponse.success(
                service.publish(cycleId, request, idempotencyKey, correlationId));
    }

    @GetMapping("/command-receipts/{receiptId}")
    public ApiResponse<PerformanceCycleDtos.CommandReceipt> receipt(
            @PathVariable UUID receiptId) {
        return ApiResponse.success(service.receipt(receiptId));
    }
}
