package com.dwp.services.synapsex.dto.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A1) GET /documents 응답 row
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentListRowDto {

    private String bukrs;
    private String belnr;
    private String gjahr;
    private LocalDate budat;
    private String usnam;
    private String tcode;
    private String blart;
    private String waers;
    private String xblnr;
    private String bktxt;
    private String statusCode;
    private String reversalBelnr;
    private Instant lastChangeTs;
    private DocumentTotalsDto totals;
    private PartnerSummaryDto partnerSummary;
    private DocumentLinksDto links;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentTotalsDto {
        private int itemCount;
        private BigDecimal totalWrbtr;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartnerSummaryDto {
        private List<String> topLifnr;
        private List<String> topKunnr;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentLinksDto {
        private String docKey;  // "{bukrs}-{belnr}-{gjahr}"
    }
}
