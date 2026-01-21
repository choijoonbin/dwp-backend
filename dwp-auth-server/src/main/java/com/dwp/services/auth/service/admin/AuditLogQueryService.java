package com.dwp.services.auth.service.admin;

import com.dwp.services.auth.dto.admin.AuditLogItem;
import com.dwp.services.auth.dto.admin.ExportAuditLogsRequest;
import com.dwp.services.auth.dto.admin.PageResponse;
import com.dwp.services.auth.entity.AuditLog;
import com.dwp.services.auth.repository.AuditLogRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PR-08A: 감사 로그 조회 서비스 (CQRS: Query 전용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@SuppressWarnings("null")
public class AuditLogQueryService {
    
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    
    // PR-08B: before/after 최대 길이 제한 (10KB)
    private static final int MAX_METADATA_LENGTH = 10 * 1024;
    
    /**
     * PR-08A: 감사 로그 목록 조회
     */
    public PageResponse<AuditLogItem> getAuditLogs(
            Long tenantId, int page, int size,
            LocalDateTime from, LocalDateTime to,
            Long actorUserId, String actionType, String resourceType, String keyword) {
        // 페이징 크기 제한 (최대 200)
        if (size > 200) {
            size = 200;
        }
        if (size < 1) {
            size = 20;
        }
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<AuditLog> auditLogPage = auditLogRepository.findByTenantIdAndFilters(
                tenantId, from, to, actorUserId, actionType, resourceType, keyword, pageable);
        
        List<AuditLogItem> items = auditLogPage.getContent().stream()
                .map(this::toAuditLogItem)
                .collect(Collectors.toList());
        
        return PageResponse.<AuditLogItem>builder()
                .items(items)
                .page(page)
                .size(size)
                .totalItems(auditLogPage.getTotalElements())
                .totalPages(auditLogPage.getTotalPages())
                .build();
    }
    
    /**
     * PR-08A: AuditLog -> AuditLogItem 변환
     * PR-08B: before/after JSON size 정책 적용
     */
    private AuditLogItem toAuditLogItem(AuditLog auditLog) {
        Map<String, Object> metadata = new HashMap<>();
        boolean truncated = false;
        
        if (auditLog.getMetadataJson() != null) {
            try {
                // JSON 파싱
                Map<String, Object> parsedMetadata = objectMapper.readValue(
                        auditLog.getMetadataJson(), new TypeReference<Map<String, Object>>() {});
                
                // PR-08B: before/after 크기 체크 및 truncate
                // PR-08B: before/after 크기 체크 및 truncate
                if (parsedMetadata.containsKey("before")) {
                    Object before = parsedMetadata.get("before");
                    String beforeJson = objectMapper.writeValueAsString(before);
                    if (beforeJson.length() > MAX_METADATA_LENGTH) {
                        // JSON이 유효하지 않을 수 있으므로 문자열로 저장
                        parsedMetadata.put("before", beforeJson.substring(0, MAX_METADATA_LENGTH) + "...[truncated]");
                        truncated = true;
                    }
                }
                
                if (parsedMetadata.containsKey("after")) {
                    Object after = parsedMetadata.get("after");
                    String afterJson = objectMapper.writeValueAsString(after);
                    if (afterJson.length() > MAX_METADATA_LENGTH) {
                        // JSON이 유효하지 않을 수 있으므로 문자열로 저장
                        parsedMetadata.put("after", afterJson.substring(0, MAX_METADATA_LENGTH) + "...[truncated]");
                        truncated = true;
                    }
                }
                
                metadata = parsedMetadata;
            } catch (Exception e) {
                log.warn("Failed to parse audit log metadata: auditLogId={}", auditLog.getAuditLogId(), e);
                metadata.put("raw", auditLog.getMetadataJson());
            }
        }
        
        return AuditLogItem.builder()
                .auditLogId(auditLog.getAuditLogId())
                .tenantId(auditLog.getTenantId())
                .actorUserId(auditLog.getActorUserId())
                .action(auditLog.getAction())
                .resourceType(auditLog.getResourceType())
                .resourceId(auditLog.getResourceId())
                .metadata(metadata)
                .truncated(truncated) // PR-08B: truncated 플래그
                .createdAt(auditLog.getCreatedAt())
                .build();
    }
    
    /**
     * PR-08C: 감사 로그 Excel 다운로드
     * 
     * @param tenantId 테넌트 ID
     * @param request 필터 조건
     * @return Excel 파일 바이트 배열
     */
    @Transactional(readOnly = true)
    public byte[] exportAuditLogsToExcel(Long tenantId, ExportAuditLogsRequest request) throws IOException {
        // 최대 row 수 제한 (기본 100)
        int maxRows = request.getMaxRows() != null ? request.getMaxRows() : 100;
        Pageable pageable = PageRequest.of(0, maxRows);
        
        Page<AuditLog> auditLogPage = auditLogRepository.findByTenantIdAndFilters(
                tenantId, request.getFrom(), request.getTo(),
                request.getActorUserId(), request.getActionType(),
                request.getResourceType(), request.getKeyword(),
                pageable);
        
        List<AuditLog> auditLogs = auditLogPage.getContent();
        
        // Excel 워크북 생성
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            
            Sheet sheet = workbook.createSheet("Audit Logs");
            
            // 헤더 스타일
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            
            // 헤더 생성
            Row headerRow = sheet.createRow(0);
            String[] headers = {"ID", "Tenant ID", "Actor User ID", "Action", "Resource Type", 
                               "Resource ID", "Metadata", "Created At"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }
            
            // 데이터 행 생성
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            int rowNum = 1;
            for (AuditLog auditLog : auditLogs) {
                Row row = sheet.createRow(rowNum++);
                
                row.createCell(0).setCellValue(auditLog.getAuditLogId() != null ? auditLog.getAuditLogId() : 0);
                row.createCell(1).setCellValue(auditLog.getTenantId() != null ? auditLog.getTenantId() : 0);
                row.createCell(2).setCellValue(auditLog.getActorUserId() != null ? auditLog.getActorUserId() : 0);
                row.createCell(3).setCellValue(auditLog.getAction() != null ? auditLog.getAction() : "");
                row.createCell(4).setCellValue(auditLog.getResourceType() != null ? auditLog.getResourceType() : "");
                row.createCell(5).setCellValue(auditLog.getResourceId() != null ? auditLog.getResourceId() : 0);
                row.createCell(6).setCellValue(auditLog.getMetadataJson() != null ? auditLog.getMetadataJson() : "");
                row.createCell(7).setCellValue(
                        auditLog.getCreatedAt() != null ? auditLog.getCreatedAt().format(formatter) : "");
            }
            
            // 컬럼 너비 자동 조정
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }
}
