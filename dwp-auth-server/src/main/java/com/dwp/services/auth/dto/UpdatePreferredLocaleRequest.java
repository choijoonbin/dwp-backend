package com.dwp.services.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdatePreferredLocaleRequest(
        @NotBlank @Size(max = 35) String locale) {
}
