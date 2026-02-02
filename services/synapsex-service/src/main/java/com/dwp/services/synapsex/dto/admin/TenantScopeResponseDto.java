package com.dwp.services.synapsex.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Tenant Scope 전체 응답 (Company Codes, Currencies, SoD Rules).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantScopeResponseDto {

    private List<CompanyCodeDto> companyCodes;
    private List<CurrencyDto> currencies;
    private List<SodRuleDto> sodRules;
    private TenantScopeMetaDto meta;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompanyCodeDto {
        private String bukrs;
        private Boolean enabled;
        private String source;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrencyDto {
        private String waers;
        private Boolean enabled;
        private String fxControlMode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SodRuleDto {
        private String ruleKey;
        private String title;
        private String description;
        private Boolean enabled;
        private String severity;
        private List<String> appliesTo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TenantScopeMetaDto {
        private Long tenantId;
        private Instant lastUpdatedAt;
        private Boolean seeded;
    }
}
