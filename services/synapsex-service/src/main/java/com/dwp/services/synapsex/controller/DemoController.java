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
 * 규정집 기반 위반/정상 시나리오 전표 생성 및 즉시 탐지 트리거.
 * Gateway: /api/synapse/demo/** → /synapse/demo/**
 */
@Tag(name = "Demo", description = "시연 데이터 제어 (위반 시나리오 생성)")
@RestController
@RequestMapping("/synapse/demo")
@RequiredArgsConstructor
public class DemoController {

    private final DemoViolationService demoViolationService;

    @Operation(summary = "위반 시나리오 생성", description = "규정집 기반 주말식대/한도초과/심야결제/정상 시나리오 전표 생성 후 즉시 탐지 및 워크벤치 알림")
    @PostMapping("/generate-violation")
    public ApiResponse<GenerateViolationResponse> generateViolation(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestBody @Valid GenerateViolationRequest request) {
        GenerateViolationResponse response = demoViolationService.generateViolation(tenantId, request);
        return ApiResponse.success(response);
    }
}
