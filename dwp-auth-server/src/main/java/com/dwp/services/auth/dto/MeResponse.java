package com.dwp.services.auth.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class MeResponse {

    private Long userId;
    private String displayName;
    private String email;
    private String jobTitle;
    private Long tenantId;
    private String tenantCode;
    private List<String> roles;
}
