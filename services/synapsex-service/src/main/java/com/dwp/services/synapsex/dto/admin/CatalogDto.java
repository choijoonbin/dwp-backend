package com.dwp.services.synapsex.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Catalog 응답 (BUKRS, WAERS 카탈로그).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogDto {

    private List<CompanyCodeCatalogItem> companyCodes;
    private List<CurrencyCatalogItem> currencies;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompanyCodeCatalogItem {
        private String bukrs;
        private Long docCount;
        private Instant lastSeenAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrencyCatalogItem {
        private String waers;
        private Long docCount;
        private Instant lastSeenAt;
    }
}
