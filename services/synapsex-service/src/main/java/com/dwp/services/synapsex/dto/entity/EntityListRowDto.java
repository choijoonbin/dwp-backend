package com.dwp.services.synapsex.dto.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * C1) GET /entities 응답 row
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityListRowDto {

    private Long partyId;
    private String partyType;
    private String partyCode;
    private String nameDisplay;
    private String country;
    private Double riskScore;
    private int openItemsCount;
    private BigDecimal totalOpenAmount;
    private Instant lastChangeTs;
}
