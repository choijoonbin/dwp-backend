package com.dwp.services.synapsex.dto.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * C2) GET /entities/{partyId} Entity 360 응답
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Entity360Dto {

    private EntityBaseDto base;
    private ExposureSummaryDto exposureSummary;
    private RiskTrendDto riskTrend;
    private List<SensitiveChangeDto> sensitiveChangesTimeline;
    private EntityTabsDto tabs;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityBaseDto {
        private Long partyId;
        private String partyType;
        private String partyCode;
        private String nameDisplay;
        private String country;
        private String lastChangeTs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExposureSummaryDto {
        private String totalOpenAmount;
        private String overdueAmount;
        private Integer avgPaymentDays;  // mockable
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskTrendDto {
        private Map<String, Long> caseCountByDay;  // last N days
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SensitiveChangeDto {
        private String changenr;
        private String udate;
        private String utime;
        private String tabname;
        private String fname;
        private String valueOld;
        private String valueNew;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityTabsDto {
        private List<LinkedDocumentDto> linkedDocuments;
        private List<LinkedOpenItemDto> linkedOpenItems;
        private List<LinkedCaseDto> linkedCases;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinkedDocumentDto {
        private String docKey;
        private String budat;
        private String xblnr;
        private String bktxt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinkedOpenItemDto {
        private String bukrs;
        private String belnr;
        private String gjahr;
        private String buzei;
        private String openAmount;
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
