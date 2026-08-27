package com.dwp.services.provider.support;

import com.dwp.core.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/support-access-requests")
public class ProviderSupportPostReviewEvidenceController {

    private final ProviderSupportPostReviewEvidenceService service;

    public ProviderSupportPostReviewEvidenceController(
            ProviderSupportPostReviewEvidenceService service) {
        this.service = service;
    }

    @GetMapping("/{requestId}/post-review-evidence")
    public ApiResponse<ProviderSupportPostReviewEvidenceDtos.Evidence> evidence(
            @PathVariable UUID requestId) {
        return ApiResponse.success(service.evidence(requestId));
    }
}
