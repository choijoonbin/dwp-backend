package com.dwp.services.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class LoginRequest {

    @NotBlank
    @Size(max = 255)
    private String email;

    @NotBlank
    @Size(max = 128)
    private String password;

    @NotBlank
    private String tenantId;
}
