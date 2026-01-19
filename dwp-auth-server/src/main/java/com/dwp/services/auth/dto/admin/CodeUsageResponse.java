package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 코드 사용 응답 DTO
 * 
 * 메뉴별 코드 조회 API 응답
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeUsageResponse {
    
    /**
     * 코드 그룹 키별 코드 목록 맵
     * 예: { "RESOURCE_TYPE": [CodeItem...], "SUBJECT_TYPE": [CodeItem...] }
     */
    private Map<String, List<CodeItem>> codes;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CodeItem {
        private Long sysCodeId;
        private String code; // codeKey
        private String name; // codeName
        private String description;
        private Integer sortOrder;
        private Boolean enabled; // isActive
        private String ext1;
        private String ext2;
        private String ext3;
    }
}
