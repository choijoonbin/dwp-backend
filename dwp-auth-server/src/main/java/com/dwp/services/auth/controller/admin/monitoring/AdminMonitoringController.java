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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Admin 모니터링 조회 API 컨트롤러
 * 
 * JWT 인증이 필요하며, Admin 권한 체크는 향후 확장 가능하도록 TODO로 남김.
 */
@Slf4j
@RestController
@RequestMapping("/admin/monitoring")
public class AdminMonitoringController {

    private final MonitoringService monitoringService;
    private final AdminMonitoringService adminMonitoringService;

    public AdminMonitoringController(MonitoringService monitoringService, AdminMonitoringService adminMonitoringService) {
        this.monitoringService = monitoringService;
        this.adminMonitoringService = adminMonitoringService;
    }
    
    /**
     * 모니터링 요약 정보 조회 (SLI/SLO KPI 포함)
     * GET /api/admin/monitoring/summary?from=ISO&to=ISO
     * optional: compareFrom=ISO&compareTo=ISO (미전달 시 직전 동일 길이 기간 자동 계산)
     */
    @GetMapping("/summary")
    // TODO: ADMIN 권한 체크 (@PreAuthorize("hasRole('ADMIN')") 또는 Service 레벨 체크)
    public ApiResponse<MonitoringSummaryResponse> getSummary(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String compareFrom,
            @RequestParam(required = false) String compareTo) {
        
        log.debug("getSummary 호출: tenantId={}, from={}, to={}, compareFrom={}, compareTo={}", tenantId, from, to, compareFrom, compareTo);
        
        LocalDateTime defaultTo = to != null ? convertUtcToKst(to) : LocalDateTime.now();
        LocalDateTime defaultFrom = from != null ? convertUtcToKst(from) : defaultTo.minusDays(30);
        LocalDateTime compareFromDt = compareFrom != null ? convertUtcToKst(compareFrom) : null;
        LocalDateTime compareToDt = compareTo != null ? convertUtcToKst(compareTo) : null;
        
        MonitoringSummaryResponse summary = monitoringService.getSummary(
                tenantId, defaultFrom, defaultTo, compareFromDt, compareToDt);
        log.debug("getSummary 결과: pv={}, uv={}, kpi={}", summary.getPv(), summary.getUv(), summary.getKpi() != null);
        
        return ApiResponse.success(summary);
    }
    
    /**
     * 페이지뷰 목록 조회 (페이징)
     * GET /api/admin/monitoring/page-views
     */
    @GetMapping("/page-views")
    public ApiResponse<Page<PageViewEvent>> getPageViews(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String route,
            @RequestParam(required = false) String menu,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) Long userId) {
        
        log.debug("getPageViews 호출: tenantId={}, from={}, to={}, page={}, size={}", 
                tenantId, from, to, page, size);
        
        // UTC 시간을 KST로 변환
        LocalDateTime fromDateTime = from != null ? convertUtcToKst(from) : null;
        LocalDateTime toDateTime = to != null ? convertUtcToKst(to) : null;
        
        Pageable pageable = PageRequest.of(page - 1, size); // 1-base to 0-base
        Page<PageViewEvent> result = monitoringService.getPageViews(
                tenantId, fromDateTime, toDateTime, keyword, route, menu, path, userId, pageable);
        log.debug("getPageViews 결과: totalElements={}", result.getTotalElements());
        
        return ApiResponse.success(result);
    }
    
    /**
     * API 호출 이력 조회 (페이징, 드릴다운: statusGroup, path, minLatencyMs, maxLatencyMs, sort)
     * GET /api/admin/monitoring/api-histories
     */
    @GetMapping("/api-histories")
    public ApiResponse<Page<ApiCallHistory>> getApiHistories(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String apiName,
            @RequestParam(required = false) String apiUrl,
            @RequestParam(required = false) Integer statusCode,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String statusGroup,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) Long minLatencyMs,
            @RequestParam(required = false) Long maxLatencyMs,
            @RequestParam(required = false, defaultValue = "TIME_DESC") String sort) {
        
        LocalDateTime fromDateTime = from != null ? convertUtcToKst(from) : null;
        LocalDateTime toDateTime = to != null ? convertUtcToKst(to) : null;
        Pageable paging = PageRequest.of(page - 1, size);
        Page<ApiCallHistory> result = monitoringService.getApiHistories(tenantId, fromDateTime, toDateTime, keyword, apiName, apiUrl, statusCode, userId, statusGroup, path, minLatencyMs, maxLatencyMs, sort, paging);
        return ApiResponse.success(result);
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
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String keyword) {
        
        log.debug("getVisitors 호출: tenantId={}, from={}, to={}, keyword={}", tenantId, from, to, keyword);
        
        // UTC 시간을 KST로 변환
        LocalDateTime defaultTo = to != null ? convertUtcToKst(to) : LocalDateTime.now();
        LocalDateTime defaultFrom = from != null ? convertUtcToKst(from) : defaultTo.minusDays(30);
        
        log.debug("getVisitors 파라미터 적용: tenantId={}, defaultFrom={}, defaultTo={}", tenantId, defaultFrom, defaultTo);
        
        Pageable pageable = PageRequest.of(page - 1, size); // 1-base to 0-base
        Page<VisitorSummary> result = adminMonitoringService.getVisitors(tenantId, defaultFrom, defaultTo, keyword, pageable);
        log.debug("getVisitors 결과: totalElements={}", result.getTotalElements());
        
        return ApiResponse.success(result);
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
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String resourceKey,
            @RequestParam(required = false) String keyword) {
        
        log.debug("getEvents 호출: tenantId={}, from={}, to={}, eventType={}, resourceKey={}, keyword={}", 
                tenantId, from, to, eventType, resourceKey, keyword);
        
        // UTC 시간을 KST로 변환
        LocalDateTime defaultTo = to != null ? convertUtcToKst(to) : LocalDateTime.now();
        LocalDateTime defaultFrom = from != null ? convertUtcToKst(from) : defaultTo.minusDays(30);
        
        log.debug("getEvents 파라미터 적용: tenantId={}, defaultFrom={}, defaultTo={}", tenantId, defaultFrom, defaultTo);
        
        Pageable pageable = PageRequest.of(page - 1, size); // 1-base to 0-base
        Page<EventLogItem> result = adminMonitoringService.getEvents(
                tenantId, defaultFrom, defaultTo, eventType, resourceKey, keyword, pageable);
        log.debug("getEvents 결과: totalElements={}", result.getTotalElements());
        
        return ApiResponse.success(result);
    }
    
    /**
     * 시계열 데이터 조회
     * GET /api/admin/monitoring/timeseries
     * @param interval 그룹 단위: 1m(1분), 5m(5분), 1h(1시간), 1d(1일) 또는 HOUR, DAY. metric=LATENCY_P95 시 해당 구간별 p95(ms) 배열 반환, 갭은 이전 값으로 채움.
     */
    @GetMapping("/timeseries")
    public ApiResponse<TimeseriesResponse> getTimeseries(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "DAY") String interval,
            @RequestParam(defaultValue = "PV") String metric) {
        
        log.debug("getTimeseries 호출: tenantId={}, from={}, to={}, interval={}, metric={}", 
                tenantId, from, to, interval, metric);
        
        // UTC 시간을 KST로 변환
        LocalDateTime defaultTo = to != null ? convertUtcToKst(to) : LocalDateTime.now();
        LocalDateTime defaultFrom = from != null ? convertUtcToKst(from) : defaultTo.minusDays(30);
        
        log.debug("getTimeseries 파라미터 적용: tenantId={}, defaultFrom={}, defaultTo={}, interval={}, metric={}", 
                tenantId, defaultFrom, defaultTo, interval, metric);
        
        TimeseriesResponse response = adminMonitoringService.getTimeseries(tenantId, defaultFrom, defaultTo, interval, metric);
        log.debug("getTimeseries 결과: labels={}, values={}", response.getLabels(), response.getValues());
        
        return ApiResponse.success(response);
    }
    
    /**
     * UTC 시간 문자열을 KST(한국 표준시) LocalDateTime으로 변환
     * 
     * 프론트엔드에서 UTC 시간을 보내면, 이를 서버의 로컬 타임존(KST)으로 변환합니다.
     * 예: UTC "2026-01-20T04:42:00" → KST "2026-01-20T13:42:00"
     * 
     * @param utcDateTimeString UTC 시간 문자열 (ISO-8601 형식, 예: "2026-01-20T04:42:00")
     * @return KST LocalDateTime
     */
    private LocalDateTime convertUtcToKst(String utcDateTimeString) {
        try {
            // ISO-8601 형식 파싱 (타임존 정보 없으면 UTC로 간주)
            LocalDateTime utcDateTime = LocalDateTime.parse(utcDateTimeString, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            
            // UTC로 해석하여 ZonedDateTime 생성
            ZonedDateTime utcZoned = utcDateTime.atZone(ZoneId.of("UTC"));
            
            // KST(Asia/Seoul, UTC+9)로 변환
            ZonedDateTime kstZoned = utcZoned.withZoneSameInstant(ZoneId.of("Asia/Seoul"));
            
            // LocalDateTime으로 변환
            LocalDateTime kstDateTime = kstZoned.toLocalDateTime();
            
            log.debug("UTC → KST 변환: {} → {}", utcDateTimeString, kstDateTime);
            
            return kstDateTime;
        } catch (Exception e) {
            log.warn("UTC 시간 파싱 실패, 원본 문자열을 그대로 사용: {}", utcDateTimeString, e);
            // 파싱 실패 시 원본 문자열을 LocalDateTime으로 파싱 시도
            return LocalDateTime.parse(utcDateTimeString, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
    }
}
