package com.dwp.services.synapsex.dto.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * A2) GET /documents/{bukrs}/{belnr}/{gjahr} 응답
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentDetailDto {

    private DocumentHeaderDto header;
    private List<DocumentItemDto> items;
    private DocumentDerivedDto derived;
    private ReversalChainDto reversalChain;
    private IntegrityChecksDto integrityChecks;
    private LinkedObjectsDto linkedObjects;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentHeaderDto {
        private String bukrs;
        private String belnr;
        private String gjahr;
        private String docSource;
        private String budat;
        private String bldat;
        private String usnam;
        private String tcode;
        private String blart;
        private String waers;
        private String xblnr;
        private String bktxt;
        private String statusCode;
        private String reversalBelnr;
        private String lastChangeTs;
        private Long rawEventId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentItemDto {
        private String buzei;
        private String hkont;
        private String lifnr;
        private String kunnr;
        private String wrbtr;
        private String waers;
        private Boolean paymentBlock;
        private Boolean disputeFlag;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentDerivedDto {
        private Integer itemCount;
        private String totalWrbtr;
        private Integer paymentBlockCount;
        private Integer disputeCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReversalChainDto {
        private List<ReversalNodeDto> nodes;
        private List<ReversalEdgeDto> edges;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReversalNodeDto {
        private String docKey;
        private String belnr;
        private String reversalBelnr;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReversalEdgeDto {
        private String fromDocKey;
        private String toDocKey;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntegrityChecksDto {
        private boolean headerExists;
        private boolean itemCountPositive;
        private boolean sumWrbtrNotNull;
        private Integer openItemsConsistency;  // optional
        private Integer ingestionErrorCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinkedObjectsDto {
        private List<OpenItemSummaryDto> openItems;
        private Map<String, Long> relatedParties;  // "LIFNR:xxx" or "KUNNR:xxx" -> partyId
        private List<LinkedCaseDto> linkedCases;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OpenItemSummaryDto {
        private String bukrs;
        private String belnr;
        private String gjahr;
        private String buzei;
        private String openAmount;
        private String currency;
        private String dueDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinkedCaseDto {
        private Long caseId;
        private String caseType;
        private String severity;
        private String detectedAt;
    }
}
