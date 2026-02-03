package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.guardrail.GuardrailEvaluateRequest;
import com.dwp.services.synapsex.dto.guardrail.GuardrailEvaluateResponse;
import com.dwp.services.synapsex.dto.guardrail.GuardrailListDto;
import com.dwp.services.synapsex.dto.guardrail.GuardrailUpsertRequest;
import com.dwp.services.synapsex.service.guardrail.GuardrailCommandService;
import com.dwp.services.synapsex.service.guardrail.GuardrailEvaluateService;
import com.dwp.services.synapsex.service.guardrail.GuardrailQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Phase 3 Guardrails API
 */
@RestController
@RequestMapping("/synapse/guardrails")
@RequiredArgsConstructor
public class GuardrailController {

    private final GuardrailQueryService guardrailQueryService;
    private final GuardrailCommandService guardrailCommandService;
    private final GuardrailEvaluateService guardrailEvaluateService;

    @GetMapping
    public ApiResponse<PageResponse<GuardrailListDto>> listGuardrails(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) Boolean enabledOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        PageResponse<GuardrailListDto> result = guardrailQueryService.listGuardrails(tenantId, enabledOnly, page, size, sort);
        return ApiResponse.success(result);
    }

    @PostMapping
    public ApiResponse<GuardrailListDto> createGuardrail(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @Valid @RequestBody GuardrailUpsertRequest request) {
        GuardrailListDto dto = guardrailCommandService.create(tenantId, request, actorUserId);
        return ApiResponse.success(dto);
    }

    @PutMapping("/{guardrailId}")
    public ApiResponse<GuardrailListDto> updateGuardrail(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable Long guardrailId,
            @Valid @RequestBody GuardrailUpsertRequest request) {
        GuardrailListDto dto = guardrailCommandService.update(tenantId, guardrailId, request, actorUserId);
        return ApiResponse.success(dto);
    }

    @DeleteMapping("/{guardrailId}")
    public ApiResponse<Void> deleteGuardrail(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @PathVariable Long guardrailId) {
        guardrailCommandService.delete(tenantId, guardrailId, actorUserId);
        return ApiResponse.success(null);
    }

    @PostMapping("/evaluate")
    public ApiResponse<GuardrailEvaluateResponse> evaluate(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestBody GuardrailEvaluateRequest request) {
        GuardrailEvaluateResponse result = guardrailEvaluateService.evaluate(tenantId, request);
        return ApiResponse.success(result);
    }
}
