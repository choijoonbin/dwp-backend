package com.dwp.services.synapsex.dto.admin;

import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatchCurrencyRequest {
    private Boolean enabled;
    @Pattern(regexp = "ALLOW|FX_REQUIRED|FX_LOCKED", message = "fxControlMode는 ALLOW, FX_REQUIRED, FX_LOCKED 중 하나여야 합니다")
    private String fxControlMode;
}
