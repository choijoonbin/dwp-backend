package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * P1-5: 코드 사용 정의 상세 DTO
 * CodeUsageSummary + createdBy, updatedBy (BaseEntity)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeUsageDetail {

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
    private Long createdBy;
    private Long updatedBy;
}
