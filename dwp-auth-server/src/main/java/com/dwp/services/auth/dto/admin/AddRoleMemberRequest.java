package com.dwp.services.auth.dto.admin;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 역할 멤버 추가 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddRoleMemberRequest {
    
    @NotNull(message = "subjectType은 필수입니다")
    private String subjectType;  // USER, DEPARTMENT
    
    @NotNull(message = "subjectId는 필수입니다")
    private Long subjectId;  // userId or departmentId
}
