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
public class TenantScopeCompanyCodesResponseDto {

    private Long profileId;
    private Instant lastUpdatedAt;  // policy_scope_company, policy_scope_currency, policy_sod_rule 중 최근 시각
    private List<CompanyCodeItemDto> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompanyCodeItemDto {
        private String bukrs;
        private String bukrsName;
        private String defaultCurrency;
        private Boolean isActive;
        private Boolean included;
        private Instant lastSyncTs;
    }
}
