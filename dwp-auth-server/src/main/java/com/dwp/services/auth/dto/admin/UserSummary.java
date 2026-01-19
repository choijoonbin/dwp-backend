package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자 목록 항목 DTO (UserListItem)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSummary {
    
    private Long comUserId; // user_id
    private Long tenantId;
    private Long departmentId;
    private String userName; // displayName
    private String email;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
