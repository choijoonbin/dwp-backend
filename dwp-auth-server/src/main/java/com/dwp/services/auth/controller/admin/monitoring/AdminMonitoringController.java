package com.dwp.services.auth.controller.admin.monitoring;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.MonitoringSummaryResponse;
import com.dwp.services.auth.dto.monitoring.EventLogItem;
import com.dwp.services.auth.dto.monitoring.TimeseriesResponse;
import com.dwp.services.auth.dto.monitoring.VisitorSummary;
import com.dwp.services.auth.entity.ApiCallHistory;
import com.dwp.services.auth.entity.PageViewEvent;
import com.dwp.services.auth.service.MonitoringService;
import com.dwp.services.auth.service.monitoring.AdminMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * Admin 모니터링 조회 API 컨트롤러
 * 
 * JWT 인증이 필요하며, Admin 권한 체크는 향후 확장 가능하도록 TODO로 남김.
 */
@RestController
@RequestMapping("/admin/monitoring")
@RequiredArgsConstructor
public class AdminMonitoringController {
    
    private final MonitoringService monitoringService;
    private final AdminMonitoringService adminMonitoringService;
    
    /**
     * 모니터링 요약 정보 조회
     * GET /api/admin/monitoring/summary
     */
    @GetMapping("/summary")
    // TODO: ADMIN 권한 체크 (@PreAuthorize("hasRole('ADMIN')") 또는 Service 레벨 체크)
    public ApiResponse<MonitoringSummaryResponse> getSummary(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        
        return ApiResponse.success(monitoringService.getSummary(tenantId, from, to));
    }
    
    /**
     * 페이지뷰 목록 조회 (페이징)
     * GET /api/admin/monitoring/page-views
     */
    @GetMapping("/page-views")
    public ApiResponse<Page<PageViewEvent>> getPageViews(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Pageable pageable = PageRequest.of(page, size);
        return ApiResponse.success(monitoringService.getPageViews(tenantId, pageable));
    }
    
    /**
     * API 호출 이력 조회 (페이징)
     * GET /api/admin/monitoring/api-histories
     */
    @GetMapping("/api-histories")
    public ApiResponse<Page<ApiCallHistory>> getApiHistories(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Pageable pageable = PageRequest.of(page, size);
        return ApiResponse.success(monitoringService.getApiHistories(tenantId, pageable));
    }
    
    /**
     * 방문자 목록 조회
     * GET /api/admin/monitoring/visitors
     */
    @GetMapping("/visitors")
    public ApiResponse<Page<VisitorSummary>> getVisitors(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String keyword) {
        
        Pageable pageable = PageRequest.of(page - 1, size); // 1-base to 0-base
        return ApiResponse.success(adminMonitoringService.getVisitors(tenantId, from, to, keyword, pageable));
    }
    
    /**
     * 이벤트 로그 목록 조회
     * GET /api/admin/monitoring/events
     */
    @GetMapping("/events")
    public ApiResponse<Page<EventLogItem>> getEvents(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String resourceKey,
            @RequestParam(required = false) String keyword) {
        
        Pageable pageable = PageRequest.of(page - 1, size); // 1-base to 0-base
        return ApiResponse.success(adminMonitoringService.getEvents(
                tenantId, from, to, eventType, resourceKey, keyword, pageable));
    }
    
    /**
     * 시계열 데이터 조회
     * GET /api/admin/monitoring/timeseries
     */
    @GetMapping("/timeseries")
    public ApiResponse<TimeseriesResponse> getTimeseries(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "DAY") String interval,
            @RequestParam(defaultValue = "PV") String metric) {
        
        return ApiResponse.success(adminMonitoringService.getTimeseries(tenantId, from, to, interval, metric));
    }
}
