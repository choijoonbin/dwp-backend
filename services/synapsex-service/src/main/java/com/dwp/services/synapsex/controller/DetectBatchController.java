package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import io.swagger.v3.oas.annotations.Operation;
import com.dwp.services.synapsex.dto.detect.DetectRunDto;
import com.dwp.services.synapsex.dto.detect.DetectRunRequest;
import com.dwp.services.synapsex.dto.detect.SchedulerStatusDto;
import com.dwp.services.synapsex.entity.DetectRun;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.dwp.services.synapsex.service.detect.DetectBatchService;
import com.dwp.services.synapsex.service.detect.DetectRunQueryService;
import com.dwp.services.synapsex.service.detect.DetectSchedulerStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;

/**
 * Phase B: Detect 배치 수동 트리거 및 관제 API.
 * Gateway: /api/synapse/admin/detect/** → /synapse/admin/detect/**
 */
@RestController
@RequestMapping("/synapse/admin/detect")
@RequiredArgsConstructor
public class DetectBatchController {

    private final DetectBatchService detectBatchService;
    private final DetectRunQueryService detectRunQueryService;
    private final DetectSchedulerStatusService schedulerStatusService;
    private final AuditWriter auditWriter;

    @Operation(summary = "Detect Run 목록 조회", description = "Run History. from, to, status 필터, page/size/sort 지원")
    @GetMapping("/runs")
    public ApiResponse<Page<DetectRunDto>> listRuns(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20, sort = "startedAt") Pageable pageable) {
        Page<DetectRunDto> page = detectRunQueryService.search(tenantId, from, to, status, pageable);
        return ApiResponse.success(page);
    }

    @Operation(summary = "Scheduler 상태 조회", description = "enabled, last_success/fail, running 여부")
    @GetMapping("/scheduler/status")
    public ApiResponse<SchedulerStatusDto> getSchedulerStatus(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId) {
        SchedulerStatusDto dto = schedulerStatusService.getStatus(tenantId);
        return ApiResponse.success(dto);
    }

    @Operation(summary = "Detect Run 상세 조회", description = "counts_json, window, status 포함")
    @GetMapping("/runs/{runId}")
    public ApiResponse<DetectRunDto> getRun(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long runId) {
        DetectRunDto dto = detectRunQueryService.getById(tenantId, runId);
        return ApiResponse.success(dto);
    }

    @Operation(summary = "Detect Run 수동 트리거", description = "body: windowMinutes(optional, 기본 15), from/to(optional, backfill). return: runId, status(COMPLETED/FAILED/SKIPPED), counts_json")
    @PostMapping("/run")
    public ApiResponse<DetectRunDto> runDetectBatch(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId,
            @RequestHeader(value = HeaderConstants.X_TRACE_ID, required = false) String traceId,
            @RequestBody(required = false) DetectRunRequest request,
            HttpServletRequest httpRequest) {

        Instant from;
        Instant to;
        if (request != null && request.getFrom() != null && request.getTo() != null) {
            from = request.getFrom();
            to = request.getTo();
        } else {
            int minutes = (request != null && request.getWindowMinutes() != null)
                    ? request.getWindowMinutes() : 15;
            to = Instant.now();
            from = to.minusSeconds((long) minutes * 60);
        }

        DetectRun run = detectBatchService.runDetectBatch(tenantId, from, to);

        Long effectiveUserId = actorUserId != null ? actorUserId : 0L;
        String ipAddress = httpRequest != null ? httpRequest.getRemoteAddr() : null;
        String userAgent = httpRequest != null ? httpRequest.getHeader("User-Agent") : null;
        String gatewayRequestId = httpRequest != null ? httpRequest.getHeader(HeaderConstants.X_GATEWAY_REQUEST_ID) : null;

        if (run == null) {
            var skippedInfo = detectBatchService.getSkippedRunInfo(tenantId);
            auditWriter.logDetectRunManualTriggered(tenantId, effectiveUserId, null, "SKIPPED",
                    from, to, traceId, ipAddress, userAgent, gatewayRequestId);
            return ApiResponse.success(DetectRunDto.builder()
                    .status("SKIPPED")
                    .errorMessage("Advisory lock not acquired (another instance running)")
                    .runningRunId(skippedInfo != null ? skippedInfo.runId() : null)
                    .runningSince(skippedInfo != null ? skippedInfo.startedAt() : null)
                    .skipReason("Another detect run is in progress (advisory lock held)")
                    .build());
        }

        auditWriter.logDetectRunManualTriggered(tenantId, effectiveUserId, run.getRunId(),
                run.getStatus(), from, to, traceId, ipAddress, userAgent, gatewayRequestId);
        return ApiResponse.success(DetectRunDto.from(run));
    }
}
