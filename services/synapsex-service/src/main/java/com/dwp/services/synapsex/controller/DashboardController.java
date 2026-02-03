package com.dwp.services.synapsex.controller;

import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.dashboard.*;

import com.dwp.services.synapsex.service.dashboard.DashboardQueryService;

/**
 * 통합관제센터 대시보드 API
 * GET /api/synapse/dashboard/summary
 * GET /api/synapse/dashboard/top-risk-drivers
 * GET /api/synapse/dashboard/action-required
 * GET /api/synapse/dashboard/team-snapshot
 * GET /api/synapse/dashboard/agent-activity
 */
@RestController
@RequestMapping("/synapse/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardQueryService dashboardQueryService;
    private final AuditWriter auditWriter;

    /**
     * GET /api/synapse/dashboard/summary
     * Agent Live Status, Financial Health Index 등 대시보드 요약
     */
    @GetMapping("/summary")
    public ApiResponse<DashboardSummaryDto> getSummary(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId) {
        logDashboardViewed(tenantId, actorUserId, AuditEventConstants.TYPE_DASHBOARD_VIEWED, "summary", Map.of("widget", "summary"));
        DashboardSummaryDto dto = dashboardQueryService.getSummary(tenantId);
        return ApiResponse.success(dto);
    }

    /**
     * GET /api/synapse/dashboard/top-risk-drivers?range=24h
     * Top Risk Drivers 카드 데이터 (case_type별 집계)
     */
    @GetMapping("/top-risk-drivers")
    public ApiResponse<List<TopRiskDriverDto>> getTopRiskDrivers(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(defaultValue = "24h") String range,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId) {
        logDashboardViewed(tenantId, actorUserId, AuditEventConstants.TYPE_DASHBOARD_VIEWED, "top-risk-drivers", Map.of("range", range));
        TopRiskDriversResponseDto response = dashboardQueryService.getTopRiskDrivers(tenantId, range);
        return ApiResponse.success(response.getItems());
    }

    /**
     * GET /api/synapse/dashboard/action-required?severity=HIGH,CRITICAL
     * Action Required 카드 데이터 (승인 대기 조치)
     */
    @GetMapping("/action-required")
    public ApiResponse<List<ActionRequiredDto>> getActionRequired(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(defaultValue = "HIGH,CRITICAL") String severity,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId) {
        logDashboardViewed(tenantId, actorUserId, AuditEventConstants.TYPE_DASHBOARD_VIEWED, "action-required", Map.of("severity", severity));
        List<String> severityList = Arrays.stream(severity.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        ActionRequiredResponseDto response = dashboardQueryService.getActionRequired(tenantId, severityList);
        return ApiResponse.success(response.getItems());
    }

    /**
     * GET /api/synapse/dashboard/team-snapshot?range=24h&teamId=optional
     * 팀 현황 (분석가별 open cases, pending approvals, SLA 등)
     */
    @GetMapping("/team-snapshot")
    public ApiResponse<TeamSnapshotResponseDto> getTeamSnapshot(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(defaultValue = "24h") String range,
            @RequestParam(required = false) Long teamId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId) {
        logDashboardViewed(tenantId, actorUserId, AuditEventConstants.TYPE_DASHBOARD_VIEWED, "team-snapshot",
                Map.of("range", range, "teamId", teamId != null ? teamId : "all"));
        TeamSnapshotResponseDto response = dashboardQueryService.getTeamSnapshot(tenantId, range, teamId);
        return ApiResponse.success(response);
    }

    /**
     * GET /api/synapse/dashboard/agent-activity?range=1h&limit=50
     * GET /api/synapse/dashboard/agent-stream?range=6h&limit=50 (동일 API, 별칭)
     * 에이전트 실행 스트림 (audit_event_log 기반)
     */
    @GetMapping({"/agent-activity", "/agent-stream"})
    public ApiResponse<AgentActivityResponseDto> getAgentActivity(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(defaultValue = "6h") String range,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long actorUserId) {
        logDashboardViewed(tenantId, actorUserId, AuditEventConstants.TYPE_DASHBOARD_VIEWED, "agent-activity",
                Map.of("range", range, "limit", limit));
        AgentActivityResponseDto response = dashboardQueryService.getAgentActivity(tenantId, range, limit);
        return ApiResponse.success(response);
    }

    private void logDashboardViewed(Long tenantId, Long actorUserId, String eventType, String dashboardKey, Map<String, Object> filters) {
        if (tenantId == null) return;
        try {
            auditWriter.logDashboardViewed(tenantId, actorUserId, eventType, dashboardKey, filters);
        } catch (Exception ignored) {
            // 감사 로그 실패 시 API 응답에는 영향 없음
        }
    }
}
