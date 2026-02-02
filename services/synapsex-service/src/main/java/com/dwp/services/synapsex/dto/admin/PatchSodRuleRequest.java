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
public class PatchSodRuleRequest {
    private Boolean enabled;
    @Pattern(regexp = "INFO|WARN|BLOCK", message = "severity는 INFO, WARN, BLOCK 중 하나여야 합니다")
    private String severity;
}
