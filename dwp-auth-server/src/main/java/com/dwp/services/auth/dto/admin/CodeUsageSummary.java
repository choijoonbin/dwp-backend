package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 코드 사용 정의 요약 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeUsageSummary {
    
    private Long sysCodeUsageId;
    private Long tenantId;
    private String resourceKey;
    private String codeGroupKey;
    private String scope;
    private Boolean enabled;
    private Integer sortOrder;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
