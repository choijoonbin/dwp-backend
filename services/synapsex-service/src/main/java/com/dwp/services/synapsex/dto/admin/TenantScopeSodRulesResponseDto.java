package com.dwp.services.synapsex.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantScopeSodRulesResponseDto {

    private Long profileId;
    private String mode;  // PLANNED | BASELINE | ENFORCED
    private Instant lastUpdatedAt;  // policy_scope_company, policy_scope_currency, policy_sod_rule 중 최근 시각
    private List<SodRuleItemDto> rules;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SodRuleItemDto {
        private String ruleKey;
        private String title;
        private String description;
        private Boolean isEnabled;
        private String severity;  // INFO | WARN | BLOCK
    }
}
