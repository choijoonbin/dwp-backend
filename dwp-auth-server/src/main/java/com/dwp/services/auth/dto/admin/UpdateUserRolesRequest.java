package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 사용자 역할 업데이트 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserRolesRequest {
    
    private List<Long> roleIds;
    
    /**
     * true: 기존 역할을 모두 삭제하고 새로 추가 (replace)
     * false: 기존 역할에 추가 (append)
     */
    @Builder.Default
    private Boolean replace = true;
}
