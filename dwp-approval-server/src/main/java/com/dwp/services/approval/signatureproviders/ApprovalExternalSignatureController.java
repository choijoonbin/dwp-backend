package com.dwp.services.approval.signatureproviders;

import static com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.*;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
@ConditionalOnProperty(prefix = "dwp.approval",
        name = "external-signature-enabled", havingValue = "true")
public class ApprovalExternalSignatureController {
    private final ApprovalExternalSignatureService service;

    public ApprovalExternalSignatureController(ApprovalExternalSignatureService service) {
        this.service = service;
    }

    @GetMapping("/requests/{requestId}/external-signature-context")
    public ApiResponse<ExternalContext> context(@PathVariable UUID requestId) {
        return ApiResponse.success(service.context(requestId));
    }

    @PostMapping("/requests/{requestId}/external-signature-requests")
    public ApiResponse<ExternalReceipt> create(@PathVariable UUID requestId,
            @Valid @RequestBody ExternalCreateInput input) {
        return ApiResponse.success(service.create(requestId, input));
    }

    @GetMapping("/external-signature-requests/{signatureRequestId}")
    public ApiResponse<ExternalRequest> get(@PathVariable UUID signatureRequestId) {
        return ApiResponse.success(service.get(signatureRequestId));
    }

    @PostMapping("/external-signature-requests/{signatureRequestId}/handovers")
    public ApiResponse<ExternalReceipt> handover(@PathVariable UUID signatureRequestId,
            @Valid @RequestBody ExternalCommandInput input,
            @RequestHeader(value = "X-DWP-Step-Up-Challenge", required = false) String challenge,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-DWP-Expected-Decision-Revision", required = false) String revision,
            @RequestHeader(value = "X-DWP-Expected-Object-Version", required = false) Long version) {
        return ApiResponse.success(service.handover(signatureRequestId, input,
                ApprovalStepUpHeaders.of(challenge, key, revision, version)));
    }

    @PostMapping("/external-signature-requests/{signatureRequestId}/refresh")
    public ApiResponse<ExternalReceipt> refresh(@PathVariable UUID signatureRequestId,
            @Valid @RequestBody ExternalCommandInput input) {
        return ApiResponse.success(service.refresh(signatureRequestId, input));
    }

    @PostMapping("/external-signature-requests/{signatureRequestId}/cancel")
    public ApiResponse<ExternalReceipt> cancel(@PathVariable UUID signatureRequestId,
            @Valid @RequestBody ExternalCommandInput input) {
        return ApiResponse.success(service.cancel(signatureRequestId, input));
    }

    @GetMapping("/external-signature-requests/{signatureRequestId}/audit")
    public ApiResponse<ExternalAudit> audit(@PathVariable UUID signatureRequestId) {
        return ApiResponse.success(service.audit(signatureRequestId));
    }

    @GetMapping("/external-signature-requests/{signatureRequestId}/artifacts/{artifactId}")
    public ApiResponse<ExternalArtifact> artifact(@PathVariable UUID signatureRequestId,
                                                  @PathVariable UUID artifactId) {
        return ApiResponse.success(service.artifact(signatureRequestId, artifactId));
    }
}
