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
    /** P0-3: 금액 표시 (fi_doc_item wrbtr 합계 또는 fi_open_item open_amount) */
    private BigDecimal amount;
    /** P0-3: 통화 (fi_doc_header waers 또는 fi_open_item currency) */
    private String currency;

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
