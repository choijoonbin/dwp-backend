package com.dwp.services.auth.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PermissionDTO {

    private String resourceType;
    private String resourceKey;
    private String resourceName;
    private String permissionCode;
    private String permissionName;
    private String effect;
}
