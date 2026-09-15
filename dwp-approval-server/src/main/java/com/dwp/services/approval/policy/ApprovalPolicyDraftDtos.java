package com.dwp.services.approval.policy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

public final class ApprovalPolicyDraftDtos {
    private ApprovalPolicyDraftDtos() {
    }

    public record Create(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_.-]{2,99}") String policyKey,
            @NotBlank @Size(max = 200) String nameKo,
            @NotBlank @Size(max = 200) String nameEn,
            @NotBlank @Pattern(regexp = "IDENTITY|DECISION|SLA|DATA|SEGREGATION_OF_DUTIES")
                    String policyType,
            @NotBlank @Pattern(regexp = "BLOCK|WARN|MONITOR") String enforcementMode,
            @NotBlank @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL") String severity,
            @NotBlank @Pattern(regexp = "ACTIVE|DISABLED|RETIRED") String lifecycleState,
            @NotEmpty @Size(max = 64) Map<String, Object> rule,
            @NotBlank @Size(min = 10, max = 1000) String changeReason) {
    }
}
