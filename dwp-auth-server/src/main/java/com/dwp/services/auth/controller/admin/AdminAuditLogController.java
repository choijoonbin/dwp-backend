package com.dwp.services.auth.controller.admin;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.admin.AuditLogDetail;
import com.dwp.services.auth.dto.admin.AuditLogItem;
import com.dwp.services.auth.dto.admin.ExportAuditLogsRequest;
import com.dwp.services.auth.dto.admin.PageResponse;
import com.dwp.services.auth.service.admin.AuditLogQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * PR-08A: Admin 감사 로그 조회 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditLogController {
    
    private final AuditLogQueryService auditLogQueryService;
    
    /**
     * PR-08A: 감사 로그 목록 조회
     * GET /api/admin/audit-logs?page=1&size=20&from=2026-01-01T00:00:00&to=2026-01-31T23:59:59&actorUserId=1&actionType=USER_CREATE&resourceType=USER&keyword=admin
     */
    /**
     * PR-08A: 감사 로그 목록 조회
     * P0-2: actor(→actorUserId), action(→actionType) alias 지원. 기존 파라미터 유지(하위호환).
     * GET /api/admin/audit-logs?page=1&size=20&from=&to=&actorUserId=|actor=&actionType=|action=&resourceType=&keyword=
     */
    @GetMapping
    public ApiResponse<PageResponse<AuditLogItem>> getAuditLogs(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) Long actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String keyword) {
        
        Long effectiveActor = actorUserId != null ? actorUserId : actor;
        String effectiveAction = actionType != null ? actionType : action;
        
        log.debug("getAuditLogs 호출: tenantId={}, from={}, to={}", tenantId, from, to);
        
        LocalDateTime fromDateTime = from != null ? convertUtcToKst(from) : null;
        LocalDateTime toDateTime = to != null ? convertUtcToKst(to) : null;
        
        return ApiResponse.success(auditLogQueryService.getAuditLogs(
                tenantId, page, size, fromDateTime, toDateTime, effectiveActor, effectiveAction, resourceType, keyword));
    }
    
    /**
     * P1-9: 감사 로그 Excel 다운로드 (GET, query params). POST /export와 동일 로직.
     * GET /api/admin/audit-logs/export?from=&to=&actor=&action=&resourceType=&keyword=&maxRows=
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportAuditLogsGet(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) Long actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer maxRows) {
        LocalDateTime fromDt = from != null ? convertUtcToKst(from) : null;
        LocalDateTime toDt = to != null ? convertUtcToKst(to) : null;
        Long effectiveActor = actorUserId != null ? actorUserId : actor;
        String effectiveAction = actionType != null ? actionType : action;
        ExportAuditLogsRequest req = ExportAuditLogsRequest.builder()
                .from(fromDt).to(toDt).actorUserId(effectiveActor).actionType(effectiveAction)
                .resourceType(resourceType).keyword(keyword).maxRows(maxRows != null ? maxRows : 100)
                .build();
        try {
            byte[] excelBytes = auditLogQueryService.exportAuditLogsToExcel(tenantId, req);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment",
                    "audit-logs-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".xlsx");
            return ResponseEntity.ok().headers(headers).body(excelBytes);
        } catch (Exception e) {
            log.error("Failed to export audit logs (GET)", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * P1-4: 감사 로그 상세 조회
     * GET /api/admin/audit-logs/{id}
     */
    @GetMapping("/{id}")
    public ApiResponse<AuditLogDetail> getAuditLogDetail(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @PathVariable("id") Long id) {
        return ApiResponse.success(auditLogQueryService.getAuditLogDetail(tenantId, id));
    }
    
    /**
     * PR-08C: 감사 로그 Excel 다운로드
     * POST /api/admin/audit-logs/export
     */
    @PostMapping("/export")
    public ResponseEntity<byte[]> exportAuditLogs(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestBody ExportAuditLogsRequest request) {
        try {
            log.debug("exportAuditLogs 호출: tenantId={}, from={}, to={}", 
                    tenantId, request.getFrom(), request.getTo());
            
            // UTC 시간을 KST로 변환 (ExportAuditLogsRequest의 from/to는 LocalDateTime이므로 직접 변환 불가)
            // JSON 역직렬화 시 이미 LocalDateTime으로 변환되어 있으므로, 여기서는 그대로 사용
            // 단, 프론트엔드에서 UTC 시간을 보낸 경우를 고려하여 변환 필요
            // 하지만 RequestBody는 이미 역직렬화된 상태이므로, 프론트엔드에서 KST로 보내거나
            // 별도의 커스텀 역직렬화가 필요함. 현재는 그대로 사용
            
            byte[] excelBytes = auditLogQueryService.exportAuditLogsToExcel(tenantId, request);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", 
                    "audit-logs-" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".xlsx");
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(excelBytes);
        } catch (Exception e) {
            log.error("Failed to export audit logs", e);
            return ResponseEntity.internalServerError().build();
        }
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
