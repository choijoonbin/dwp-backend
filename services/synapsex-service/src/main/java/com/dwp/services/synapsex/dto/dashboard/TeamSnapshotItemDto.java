package com.dwp.services.synapsex.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * GET /api/synapse/dashboard/team-snapshot 응답 항목
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TeamSnapshotItemDto {

    private Long analystUserId;
    private String analystName;
    private String title;
    private long openCases;
    private String slaRisk;  // AT_RISK | ON_TRACK
    private BigDecimal avgLeadTimeHours;
    private long pendingApprovals;
    private String topQueue;  // case_type (e.g. PAYMENT_BLOCK, DUPLICATE_INVOICE)
    private Links links;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Links {
        private String casesPath;
        private String actionsPath;  // Pending Approvals 클릭용
        private String auditPath;
    }
}
