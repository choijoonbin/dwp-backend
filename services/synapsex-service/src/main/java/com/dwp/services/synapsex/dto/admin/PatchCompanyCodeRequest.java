package com.dwp.services.synapsex.dto.admin;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatchCompanyCodeRequest {
    @NotNull(message = "enabled는 필수입니다")
    private Boolean enabled;
}
