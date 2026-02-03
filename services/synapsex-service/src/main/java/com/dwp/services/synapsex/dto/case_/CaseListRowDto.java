package com.dwp.services.synapsex.dto.case_;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A1) GET /cases 응답 row
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseListRowDto {

    private Long caseId;
    private Instant detectedAt;
    private String caseType;
    private String severity;
    private BigDecimal score;
    private String status;
    private String reasonTextShort;
    private List<String> docKeys;
    private PartySummaryDto partySummary;
    private int relatedActionsCount;
    private Long assigneeUserId;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartySummaryDto {
        private Long partyId;
        private String partyCode;
        private String nameDisplay;
    }
}
