package com.dwp.services.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {
    private Long expiresIn;
    private String userId;
    private String tenantId;

    @Builder.Default
    private List<PermissionDTO> permissions = Collections.emptyList();
}
