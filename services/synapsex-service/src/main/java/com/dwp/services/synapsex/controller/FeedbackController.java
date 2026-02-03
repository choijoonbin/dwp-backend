package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.feedback.FeedbackCreateRequest;
import com.dwp.services.synapsex.dto.feedback.FeedbackLabelDto;
import com.dwp.services.synapsex.service.feedback.FeedbackCommandService;
import com.dwp.services.synapsex.service.feedback.FeedbackQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Phase 3 Feedback API
 */
@RestController
@RequestMapping("/synapse/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackQueryService feedbackQueryService;
    private final FeedbackCommandService feedbackCommandService;

    @GetMapping
    public ApiResponse<PageResponse<FeedbackLabelDto>> listFeedback(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        PageResponse<FeedbackLabelDto> result = feedbackQueryService.listByTarget(tenantId, targetType, targetId, page, size, sort);
        return ApiResponse.success(result);
    }

    @PostMapping
    public ApiResponse<FeedbackLabelDto> createFeedback(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @Valid @RequestBody FeedbackCreateRequest request) {
        FeedbackLabelDto dto = feedbackCommandService.create(tenantId, request, actorUserId);
        return ApiResponse.success(dto);
    }
}
