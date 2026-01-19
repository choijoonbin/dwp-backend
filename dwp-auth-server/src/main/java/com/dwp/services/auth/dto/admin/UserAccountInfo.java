package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자 계정 정보 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAccountInfo {
    
    private Long comUserAccountId; // user_account_id
    private String providerType;
    private String principal;
    private Boolean enabled; // status == "ACTIVE"
    private LocalDateTime lastLoginAt; // login_history에서 조회 가능
}
