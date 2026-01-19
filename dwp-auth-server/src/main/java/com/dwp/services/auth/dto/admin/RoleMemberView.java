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
    
    private String subjectType; // USER, DEPARTMENT
    private Long subjectId;
    private String subjectName; // userName or departmentName
}
