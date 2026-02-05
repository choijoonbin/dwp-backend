package com.dwp.services.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PR-06C: 코드 응답 DTO (tenant 분리 지원)
 * i18n: name/label은 Accept-Language에 따라 locale-resolved 값 반환
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeResponse {
    
    private Long sysCodeId;
    private String groupKey;
    private String code;
    /** locale-resolved 표시명 (name_ko/name_en/name fallback) */
    private String name;
    /** locale-resolved 라벨 (name과 동일, API 스펙 호환) */
    private String label;
    private String description;
    private Integer sortOrder;
    private Boolean isActive;
    private Long tenantId; // PR-06C: tenant 분리 지원 (null이면 공통 코드)
    private String ext1;
    private String ext2;
    private String ext3;
}
