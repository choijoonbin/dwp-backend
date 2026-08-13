package com.dwp.services.auth.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class MeResponse {

    private Long userId;
    private UUID personPublicId;
    private String displayName;
    private String email;
    private String jobTitle;
    private String preferredLocale;
    private String tenantDefaultLocale;
    private Long tenantId;
    private String tenantCode;
    private String tenantName;
    private List<String> roles;

    @Builder.Default
    private List<GroupMembershipDTO> groups = Collections.emptyList();

    @Builder.Default
    private List<PermissionDTO> permissions = Collections.emptyList();

    @Builder.Default
    private List<AppGovernanceDtos.ResourceRole> resourceRoles = Collections.emptyList();
}
