package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.demo.GenerateViolationRequest;
import com.dwp.services.synapsex.dto.demo.GenerateViolationResponse;
import com.dwp.services.synapsex.dto.demo.ScenarioTypeOptionDto;
import com.dwp.services.synapsex.service.demo.DemoViolationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

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

    @Operation(summary = "시나리오 유형 코드 목록 (드롭다운용)", description = "테스트 데이터 생성 시 선택할 수 있는 7개 위험 유형 코드·라벨. FE는 이 코드를 scenarioType으로 POST /generate-violation에 전달.")
    @GetMapping("/scenario-types")
    public ApiResponse<List<ScenarioTypeOptionDto>> getScenarioTypes() {
        List<ScenarioTypeOptionDto> list = Arrays.stream(GenerateViolationRequest.ScenarioType.standardCodes())
                .map(t -> ScenarioTypeOptionDto.builder()
                        .code(t.name())
                        .label(t.getLabel())
                        .build())
                .toList();
        return ApiResponse.success(list);
    }

    @Operation(summary = "위반/정상 시나리오 생성 (generate_demo_scenario)", description = "엔드투엔드 자동화: 전표 생성 → Detect → 케이스 생성 → Aura Thought Chain 자동 트리거. "
            + "Authorization·X-User-ID 헤더를 넣으면 케이스별로 Aura 분석이 자동 호출되고, analysis_started가 WebSocket(/topic/notifications)으로 전달됨. "
            + "scenarioType은 GET /synapse/demo/scenario-types 응답의 code 사용. "
            + "본문 예: {\"scenarioType\":\"SPLIT_PAYMENT\",\"intensity\":\"VIOLATION\",\"count\":1,\"limitAmountKrw\":30000}")
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
