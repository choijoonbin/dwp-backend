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
 * A1) GET /documents 응답 row (Phase1 프론트 mock-data.ts 매칭)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentListRowDto {

    /** docKey = bukrs-belnr-gjahr */
    private String docKey;
    private String bukrs;
    private String belnr;
    private String gjahr;
    private LocalDate budat;
    private LocalDate bldat;
    private String blart;
    private String tcode;
    private String usnam;
    /** Primary kunnr from items */
    private String kunnr;
    /** Primary lifnr from items */
    private String lifnr;
    /** Counterparty display name (optional) */
    private String counterpartyName;
    private BigDecimal wrbtr;
    private String waers;
    private String xblnr;
    private String bktxt;
    /** PASS|WARN|FAIL (derived from integrity checks) */
    private String integrityStatus;
    /** Has reversal_belnr */
    private Boolean reversalFlag;
    /** DocKey this doc reverses (when reversal_belnr points to another) */
    private String reversesDocKey;
    /** DocKey that reverses this doc */
    private String reversedByDocKey;
    private Integer linkedCasesCount;
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
