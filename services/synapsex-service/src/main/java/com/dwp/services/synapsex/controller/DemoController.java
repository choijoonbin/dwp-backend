package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.demo.GenerateViolationRequest;
import com.dwp.services.synapsex.dto.demo.GenerateViolationResponse;
import com.dwp.services.synapsex.service.demo.DemoViolationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 시연 데이터 제어 API.
 * 전표 생성 → Detect → 케이스 생성 → Aura Thought Chain 자동 트리거(엔드투엔드 무인).
 * Authorization, X-User-ID 헤더를 보내면 케이스 생성 직후 Aura 분석이 자동 호출되며, analysis_started가 WebSocket으로 전달됨.
 * Gateway: /api/synapse/demo/** 또는 /api/demo/** → /synapse/demo/**
 */
@Tag(name = "Demo", description = "시연 데이터 제어 (위반 시나리오 생성)")
@RestController
@RequestMapping("/synapse/demo")
@RequiredArgsConstructor
public class DemoController {

    private static final long DEFAULT_TENANT_ID = 1L;

    private final DemoViolationService demoViolationService;

    @Operation(summary = "위반/정상 시나리오 생성 (generate_demo_scenario)", description = "엔드투엔드 자동화: 전표 생성 → Detect → 케이스 생성 → Aura Thought Chain 자동 트리거. "
            + "Authorization·X-User-ID 헤더를 넣으면 케이스별로 Aura 분석이 자동 호출되고, analysis_started가 WebSocket(/topic/notifications)으로 전달됨. "
            + "본문 예: {\"scenarioType\":\"LATE_NIGHT\",\"intensity\":\"VIOLATION\",\"count\":2,\"limitAmountKrw\":30000}")
    @PostMapping(value = { "/generate", "/generate-violation" })
    public ApiResponse<GenerateViolationResponse> generateViolation(
            @RequestHeader(value = HeaderConstants.X_TENANT_ID, required = false) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) @Valid GenerateViolationRequest request) {
        long effectiveTenantId = tenantId != null ? tenantId : DEFAULT_TENANT_ID;
        GenerateViolationRequest body = request != null ? request : GenerateViolationRequest.builder().build();
        GenerateViolationResponse response = demoViolationService.generateViolation(
                effectiveTenantId, body, authorization, userId);
        return ApiResponse.success(response);
    }
}
