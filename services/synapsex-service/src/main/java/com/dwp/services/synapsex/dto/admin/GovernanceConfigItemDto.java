package com.dwp.services.synapsex.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 거버넌스 설정 항목 (config_key + 현재값 + 선택지).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceConfigItemDto {

    private String configKey;       // SECURITY_ACCESS_MODEL, SECURITY_SOD_MODE, ...
    private String groupName;      // 그룹 표시명
    private String currentValue;   // 현재 저장된 코드 값 (예: RBAC)
    private List<CodeOptionDto> options;  // 선택 가능한 코드 목록

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CodeOptionDto {
        private String code;   // RBAC, ENFORCED, ...
        private String name;  // Role-based, Enforced, ...
    }
}
