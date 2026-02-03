package com.dwp.services.synapsex.dto.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * C1) GET /entities 응답 row (Phase1 프론트 mock-data.ts 매칭)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityListRowDto {

    private Long partyId;
    private String type;  // VENDOR|CUSTOMER
    private String name;
    private String country;
    private Double riskScore;  // 0-100
    private String riskTrend;  // UP|DOWN|STABLE
    private Integer openItemsCount;
    private BigDecimal openItemsTotal;
    private Integer overdueCount;
    private BigDecimal overdueTotal;
    private Integer recentAnomaliesCount;
    private Instant lastChangedAt;
}
