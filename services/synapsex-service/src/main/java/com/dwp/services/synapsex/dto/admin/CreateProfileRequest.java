package com.dwp.services.synapsex.dto.admin;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateProfileRequest {
    @NotBlank(message = "profileName is required")
    private String profileName;
    private String description;
    private Boolean isDefault = false;
}
