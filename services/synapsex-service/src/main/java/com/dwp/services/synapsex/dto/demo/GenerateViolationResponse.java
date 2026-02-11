package com.dwp.services.synapsex.dto.demo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 시연용 위반 시나리오 생성 결과.
 * 생성된 전표(doc) 및 탐지로 생성된 케이스 ID 목록, Run ID 포함.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "시연 위반 시나리오 생성 결과")
public class GenerateViolationResponse {

    @Schema(description = "생성된 전표 식별자 목록 (bukrs-belnr-gjahr)")
    private List<String> createdDocKeys;

    @Schema(description = "탐지 Run으로 생성/갱신된 케이스 ID 목록")
    private List<Long> createdCaseIds;

    @Schema(description = "Detect Run ID (즉시 탐지 배치 실행 시)")
    private Long detectRunId;

    @Schema(description = "Run 상태 (COMPLETED, FAILED, SKIPPED)")
    private String detectRunStatus;

    @Schema(description = "안내 메시지")
    private String message;
}
