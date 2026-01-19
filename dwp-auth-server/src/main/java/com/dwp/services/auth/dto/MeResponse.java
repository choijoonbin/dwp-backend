package com.dwp.services.auth.dto;

import lombok.*;

import java.util.List;

/**
 * 내 정보 응답 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MeResponse {
    
    private Long userId;
    private String displayName;
    private String email;
    private Long tenantId;
    private String tenantCode;
    private List<String> roles;  // optional: role codes
}
