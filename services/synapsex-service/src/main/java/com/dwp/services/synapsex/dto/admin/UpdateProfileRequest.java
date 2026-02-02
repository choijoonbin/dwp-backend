package com.dwp.services.synapsex.dto.admin;

import lombok.Data;

@Data
public class UpdateProfileRequest {
    private String profileName;
    private String description;
    private Boolean isDefault;
}
