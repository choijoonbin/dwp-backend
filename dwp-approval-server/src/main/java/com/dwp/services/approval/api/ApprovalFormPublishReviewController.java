package com.dwp.services.approval.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos.*;
import com.dwp.services.approval.forms.ApprovalFormPublishReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApprovalFormPublishReviewController {
    private final ApprovalFormPublishReviewService reviews;

    public ApprovalFormPublishReviewController(ApprovalFormPublishReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/v1/admin/forms/publish-review-candidates")
    public ApiResponse<PublishReviewCandidates> candidates(@RequestParam String query,
            @RequestParam(defaultValue = "10") int size, HttpServletRequest request) {
        exactQuery(request, Set.of("query", "size"));
        return ApiResponse.success(reviews.candidates(query, size));
    }

    @GetMapping("/v1/admin/forms/publish-review-requests")
    public ApiResponse<PublishReviewQueue> queue(@RequestParam(defaultValue = "50") int size,
            HttpServletRequest request) {
        exactQuery(request, Set.of("size"));
        return ApiResponse.success(reviews.queue(size));
    }

    @GetMapping("/v1/admin/forms/{formId}/publish-review-request")
    public ApiResponse<PublishReviewRequestState> current(@PathVariable String formId,
            HttpServletRequest request) {
        exactQuery(request, Set.of());
        return ApiResponse.success(new PublishReviewRequestState(reviews.latest(uuid(formId))));
    }

    @Operation(parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER,
            required = true, schema = @Schema(type = "string", maxLength = 120)))
    @PostMapping("/v1/admin/forms/{formId}/publish-review-request")
    public ApiResponse<PublishReviewRequest> request(@PathVariable String formId,
            @Valid @RequestBody RequestPublishReview body, HttpServletRequest request) {
        exactQuery(request, Set.of());
        return ApiResponse.success(reviews.request(uuid(formId), body, header(request,
                "Idempotency-Key", true), header(request, "X-Correlation-ID", false)));
    }

    @Operation(parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER,
            required = true, schema = @Schema(type = "string", maxLength = 120)))
    @PostMapping("/v1/admin/forms/{formId}/publish-review-requests/{requestId}/reject")
    public ApiResponse<PublishReviewRequest> reject(@PathVariable String formId,
            @PathVariable String requestId, @Valid @RequestBody RejectPublishReview body,
            HttpServletRequest request) {
        exactQuery(request, Set.of());
        return ApiResponse.success(reviews.reject(uuid(formId), uuid(requestId), body,
                header(request, "Idempotency-Key", true),
                header(request, "X-Correlation-ID", false)));
    }

    private void exactQuery(HttpServletRequest request, Set<String> allowed) {
        if (!allowed.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
            throw invalid();
        }
    }

    private UUID uuid(String value) {
        if (value == null || !value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw invalid();
        }
        return UUID.fromString(value);
    }

    private String header(HttpServletRequest request, String name, boolean required) {
        var values = Collections.list(request.getHeaders(name));
        if (values.isEmpty() && !required) return null;
        if (values.size() != 1 || values.getFirst().isBlank()
                || !values.getFirst().equals(values.getFirst().trim())) throw invalid();
        return values.getFirst();
    }

    private BaseException invalid() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE);
    }
}
