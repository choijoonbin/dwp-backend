package com.dwp.services.people.workforce;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.people.security.HcmStepUpHeaders;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/workforce/exports")
public class WorkforceExportController {

    private final WorkforceExportService service;

    public WorkforceExportController(WorkforceExportService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    public ApiResponse<WorkforceExportDtos.Preview> preview(
            @Valid @RequestBody WorkforceExportDtos.PreviewRequest request) {
        return ApiResponse.success(service.preview(request));
    }

    @GetMapping("/datasets")
    public ApiResponse<List<WorkforceExportDtos.DatasetSummary>> datasets() {
        return ApiResponse.success(service.datasets());
    }

    @GetMapping
    public ApiResponse<List<WorkforceExportDtos.RequestSummary>> list() {
        return ApiResponse.success(service.list());
    }

    @PostMapping
    public ApiResponse<WorkforceExportDtos.RequestSummary> create(
            @Valid @RequestBody WorkforceExportDtos.CreateRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = HcmStepUpHeaders.CHALLENGE, required = false) String challenge,
            @RequestHeader(value = HcmStepUpHeaders.IDEMPOTENCY_KEY, required = false)
            String idempotencyKey,
            @RequestHeader(value = HcmStepUpHeaders.DECISION_REVISION, required = false)
            String decisionRevision,
            @RequestHeader(value = HcmStepUpHeaders.EXPECTED_OBJECT_VERSION, required = false)
            Long expectedObjectVersion) {
        return ApiResponse.success(service.create(request, correlationId,
                headers(challenge, idempotencyKey,
                        decisionRevision, expectedObjectVersion)));
    }

    @GetMapping("/{requestId}/attempts")
    public ApiResponse<List<WorkforceExportDtos.AttemptEvent>> attempts(
            @PathVariable UUID requestId) {
        return ApiResponse.success(service.attempts(requestId));
    }

    @PatchMapping("/{requestId}/cancel")
    public ApiResponse<WorkforceExportDtos.RequestSummary> cancel(
            @PathVariable UUID requestId,
            @Valid @RequestBody WorkforceExportDtos.DecisionRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(service.cancel(requestId, request, correlationId));
    }

    @PatchMapping("/{requestId}/retry")
    public ApiResponse<WorkforceExportDtos.RequestSummary> retry(
            @PathVariable UUID requestId,
            @Valid @RequestBody WorkforceExportDtos.DecisionRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = HcmStepUpHeaders.CHALLENGE, required = false) String challenge,
            @RequestHeader(value = HcmStepUpHeaders.IDEMPOTENCY_KEY, required = false)
            String idempotencyKey,
            @RequestHeader(value = HcmStepUpHeaders.DECISION_REVISION, required = false)
            String decisionRevision,
            @RequestHeader(value = HcmStepUpHeaders.EXPECTED_OBJECT_VERSION, required = false)
            Long expectedObjectVersion) {
        return ApiResponse.success(service.retry(requestId, request, correlationId,
                headers(challenge, idempotencyKey,
                        decisionRevision, expectedObjectVersion)));
    }

    private HcmStepUpHeaders headers(
            String challenge,
            String idempotencyKey,
            String decisionRevision,
            Long expectedObjectVersion) {
        return new HcmStepUpHeaders(
                challenge, idempotencyKey, decisionRevision, expectedObjectVersion);
    }
}
