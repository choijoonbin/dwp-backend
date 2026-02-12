package com.dwp.services.synapsex.dto.demo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 시연용 위반 시나리오 생성 결과.
 * 탐지·Aura 분석은 비동기 실행. 응답 시점에는 detectRunStatus=ASYNC_STARTED, createdCaseIds=빈 목록.
 * case_created·analysis_started 알림은 WebSocket /topic/notifications 로 실시간 전달.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "시연 위반 시나리오 생성 결과")
public class GenerateViolationResponse {

    @Schema(description = "생성된 전표 식별자 목록 (bukrs-belnr-gjahr)")
    private List<String> createdDocKeys;

    @Schema(description = "탐지로 생성된 케이스 ID 목록. 비동기 탐지 시 응답 시점에는 빈 목록, 완료 후 WebSocket 알림")
    private List<Long> createdCaseIds;

    @Schema(description = "Detect Run ID. 비동기 탐지 시 null")
    private Long detectRunId;

    @Schema(description = "Run 상태: ASYNC_STARTED(비동기 시작), COMPLETED, FAILED, SKIPPED")
    private String detectRunStatus;

    @Schema(description = "안내 메시지")
    private String message;
}
