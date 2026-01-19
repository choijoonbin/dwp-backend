package com.dwp.services.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 코드 그룹 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeGroupResponse {
    
    private Long sysCodeGroupId;
    private String groupKey;
    private String groupName;
    private String description;
    private Boolean isActive;
}
