package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 역할 멤버 뷰 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleMemberView {
    
    private Long roleMemberId;  // 추가: 개별 삭제를 위한 ID
    private String subjectType; // USER, DEPARTMENT
    private Long subjectId;
    private String subjectName; // userName or departmentName
    private String subjectEmail; // USER일 경우 email (선택)
    private String departmentName; // USER의 primary department name (선택)
}
