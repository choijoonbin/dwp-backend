package com.dwp.services.synapsex.dto.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /optimization/ar, /optimization/ap 응답
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptimizationArApDto {

    private String type;  // AR | AP
    private List<BucketDto> buckets;
    private OverdueSummaryDto overdueSummary;
    private List<AlertRecommendationDto> alertRecommendations;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BucketDto {
        private String bucketKey;   // current, 1-30, 31-90, 90+
        private int itemCount;
        private BigDecimal totalAmount;
        private String currency;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OverdueSummaryDto {
        private int overdueCount;
        private BigDecimal overdueAmount;
        private String currency;
        private Double avgDaysPastDue;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlertRecommendationDto {
        private String recommendationType;  // NUDGE, BLOCK, REVIEW
        private int affectedCount;
        private String reason;
    }
}
