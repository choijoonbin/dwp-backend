package com.dwp.services.synapsex.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * GET /api/synapse/dashboard/top-risk-drivers 응답 항목
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopRiskDriverDto {

    private String driverKey;
    private String label;
    private long caseCount;
    private BigDecimal impactAmount;

    /** drill-down용 risk type 키 (DUPLICATE_INVOICE, BANK_CHANGE 등) */
    private String riskTypeKey;
    /** 예상 손실 (impactAmount 별칭) */
    private BigDecimal estimatedLoss;
    /** 클릭 시 anomalies/cases 필터 경로 */
    private AnomaliesLinks links;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnomaliesLinks {
        private String anomaliesPath;
    }

    /** FE 계약 호환: id, type, count, amount */
    public Object getId() { return driverKey; }
    public String getType() { return driverKey; }
    public long getCount() { return caseCount; }
    public BigDecimal getAmount() { return impactAmount != null ? impactAmount : estimatedLoss; }
}
