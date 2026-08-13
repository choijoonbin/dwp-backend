package com.dwp.services.provider.governance;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/data-governance/policies")
public class DataPolicyController {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final DataPolicyService service;

    public DataPolicyController(DataPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<DataPolicyDtos.Policy>> policies() {
        return ApiResponse.success(service.policies());
    }

    @PostMapping
    public ApiResponse<DataPolicyDtos.Policy> create(
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody DataPolicyDtos.CreatePolicyRequest request) {
        return ApiResponse.success(service.create(request, correlationId));
    }

    @PostMapping("/{policyId}/revisions")
    public ApiResponse<DataPolicyDtos.Revision> createRevision(
            @PathVariable UUID policyId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody DataPolicyDtos.CreateRevisionRequest request) {
        return ApiResponse.success(service.createRevision(policyId, request, correlationId));
    }

    @PostMapping("/revisions/{revisionId}/impact-preview")
    public ApiResponse<DataPolicyDtos.Revision> preview(
            @PathVariable UUID revisionId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody DataPolicyDtos.VersionedReasonRequest request) {
        return ApiResponse.success(service.preview(revisionId, request, correlationId));
    }

    @PostMapping("/revisions/{revisionId}/submit")
    public ApiResponse<DataPolicyDtos.Revision> submit(
            @PathVariable UUID revisionId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody DataPolicyDtos.VersionedReasonRequest request) {
        return ApiResponse.success(service.submit(revisionId, request, correlationId));
    }

    @PostMapping("/revisions/{revisionId}/approval")
    public ApiResponse<DataPolicyDtos.Revision> decide(
            @PathVariable UUID revisionId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody DataPolicyDtos.ApprovalDecisionRequest request) {
        return ApiResponse.success(service.decide(revisionId, request, correlationId));
    }

    @PostMapping("/revisions/{revisionId}/publish")
    public ApiResponse<DataPolicyDtos.Revision> publish(
            @PathVariable UUID revisionId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody DataPolicyDtos.VersionedReasonRequest request) {
        return ApiResponse.success(service.publish(revisionId, request, correlationId));
    }

    @PostMapping("/revisions/{revisionId}/rollback-request")
    public ApiResponse<DataPolicyDtos.Revision> requestRollback(
            @PathVariable UUID revisionId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody DataPolicyDtos.VersionedReasonRequest request) {
        return ApiResponse.success(service.requestRollback(revisionId, request, correlationId));
    }
}
