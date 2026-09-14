package com.dwp.services.approval.signatures;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.signatures.ApprovalSignatureDtos.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/v1")
@ConditionalOnProperty(prefix="dwp.approval.internal-signatures",name="enabled",havingValue="true")
public class ApprovalSignatureController {
    private final ApprovalSignatureService service;
    public ApprovalSignatureController(ApprovalSignatureService service) { this.service=service; }
    @GetMapping("/requests/{requestId}/signature-context")
    public ApiResponse<Context> context(@PathVariable UUID requestId,@RequestParam(defaultValue="ko") @Pattern(regexp="ko|en") String locale) {
        return ApiResponse.success(service.context(requestId,locale));
    }
    @PostMapping("/requests/{requestId}/signature-requests")
    public ApiResponse<Receipt> create(@PathVariable UUID requestId,@RequestBody @Valid Create input) { return ApiResponse.success(service.create(requestId,input)); }
    @GetMapping("/signature-requests/{signatureRequestId}")
    public ApiResponse<Ceremony> get(@PathVariable UUID signatureRequestId) { return ApiResponse.success(service.get(signatureRequestId)); }
    @PostMapping("/signature-requests/{signatureRequestId}/consents")
    public ApiResponse<Receipt> consent(@PathVariable UUID signatureRequestId,@RequestBody @Valid Consent input) { return ApiResponse.success(service.consent(signatureRequestId,input)); }
    @PostMapping("/signature-requests/{signatureRequestId}/sign")
    public ApiResponse<Receipt> sign(@PathVariable UUID signatureRequestId,@RequestBody @Valid Sign input) { return ApiResponse.success(service.sign(signatureRequestId,input)); }
    @PostMapping("/signature-requests/{signatureRequestId}/cancel")
    public ApiResponse<Receipt> cancel(@PathVariable UUID signatureRequestId,@RequestBody @Valid Cancel input) { return ApiResponse.success(service.cancel(signatureRequestId,input)); }
    @GetMapping("/signature-requests/{signatureRequestId}/audit")
    public ApiResponse<Audit> audit(@PathVariable UUID signatureRequestId) { return ApiResponse.success(service.audit(signatureRequestId)); }
}
