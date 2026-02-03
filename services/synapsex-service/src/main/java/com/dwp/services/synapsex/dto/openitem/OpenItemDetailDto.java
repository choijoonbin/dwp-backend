package com.dwp.services.synapsex.dto.openitem;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * B2) GET /open-items/{openItemKey} 응답 (clearing history 포함)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenItemDetailDto {

    private String openItemKey;
    private String bukrs;
    private String belnr;
    private String gjahr;
    private String buzei;
    private String itemType;
    private String dueDate;
    private String openAmount;
    private String currency;
    private Boolean cleared;
    private Boolean paymentBlock;
    private Boolean disputeFlag;
    private String lastChangeTs;
    private DocHeaderSummaryDto docHeaderSummary;
    private PartySummaryDto partySummary;
    private List<RelatedCaseDto> relatedCases;
    private List<ClearingHistoryDto> clearingHistory;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocHeaderSummaryDto {
        private String bukrs;
        private String belnr;
        private String gjahr;
        private String budat;
        private String xblnr;
        private String bktxt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartySummaryDto {
        private Long partyId;
        private String partyType;
        private String partyCode;
        private String nameDisplay;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelatedCaseDto {
        private Long caseId;
        private String caseType;
        private String severity;
        private String detectedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClearingHistoryDto {
        private String clearingDocKey;
        private String clearingDate;
        private String clearedAmount;
    }
}
