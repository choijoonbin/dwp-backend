package com.dwp.services.approval.signatureproviders;

import static com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.*;
import static com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderPolicyDtos.*;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/admin/signatures")
@ConditionalOnProperty(prefix = "dwp.approval",
        name = "external-signature-enabled", havingValue = "true")
public class ApprovalSignatureProviderController {
    private final ApprovalSignatureProviderService service;

    public ApprovalSignatureProviderController(ApprovalSignatureProviderService service) {
        this.service = service;
    }

    @GetMapping("/diagnostics")
    public ApiResponse<Overview> diagnostics() {
        return ApiResponse.success(service.diagnostics());
    }

    @GetMapping("/providers/{providerId}/diagnostics")
    public ApiResponse<ProviderDiagnostics> provider(@PathVariable UUID providerId) {
        return ApiResponse.success(service.provider(providerId));
    }

    @GetMapping("/diagnostic-history")
    public ApiResponse<ApprovalSignatureProviderDtos.History> history(
            @RequestParam(required = false) Long cursor) {
        return ApiResponse.success(service.history(cursor));
    }

    @PostMapping("/probes")
    public ApiResponse<ProbeRun> probe(@Valid @RequestBody ProbeInput input) {
        return ApiResponse.success(service.probe(input));
    }

    @PostMapping("/kms/probes")
    public ApiResponse<Overview> probeKms(@Valid @RequestBody KmsProbeInput input) {
        return ApiResponse.success(service.probeKms(input));
    }

    @PostMapping("/worm-inspections")
    public ApiResponse<Overview> inspectWorm(@Valid @RequestBody WormInspectionInput input) {
        return ApiResponse.success(service.inspectWorm(input));
    }

    @GetMapping("/policy")
    public ApiResponse<View> policy() {
        return ApiResponse.success(service.policy());
    }

    @PostMapping("/policies")
    public ApiResponse<View> initialize(@Valid @RequestBody InitializeInput input) {
        return ApiResponse.success(service.initialize(input));
    }

    @PutMapping("/policies/{policyId}/draft")
    public ApiResponse<View> save(@PathVariable UUID policyId,
                                  @Valid @RequestBody DraftInput input) {
        return ApiResponse.success(service.save(policyId, input));
    }

    @PostMapping("/policies/{policyId}/publish")
    public ApiResponse<View> publish(@PathVariable UUID policyId,
            @Valid @RequestBody PublishInput input,
            @RequestHeader(value = "X-DWP-Step-Up-Challenge", required = false) String challenge,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-DWP-Expected-Decision-Revision", required = false) String revision,
            @RequestHeader(value = "X-DWP-Expected-Object-Version", required = false) Long version) {
        return ApiResponse.success(service.publish(policyId, input,
                ApprovalStepUpHeaders.of(challenge, key, revision, version)));
    }

    @GetMapping("/policies/{policyId}/history")
    public ApiResponse<ApprovalSignatureProviderPolicyDtos.History> policyHistory(
            @PathVariable UUID policyId, @RequestParam(required = false) Long cursor) {
        return ApiResponse.success(service.policyHistory(policyId, cursor));
    }
}
