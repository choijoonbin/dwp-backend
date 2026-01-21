package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * PR-08C: 감사 로그 Excel 다운로드 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportAuditLogsRequest {
    
    private LocalDateTime from;
    private LocalDateTime to;
    private Long actorUserId;
    private String actionType;
    private String resourceType;
    private String keyword;
    
    @Builder.Default
    private Integer maxRows = 100; // 기본 100 row 제한
}
