package com.dwp.services.synapsex.dto.recon;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartReconRequest {

    @NotBlank(message = "runType is required")
    private String runType;  // DOC_OPENITEM_MATCH, ACTION_EFFECT, etc.
}
