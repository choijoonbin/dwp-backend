package com.dwp.services.synapsex.dto.openitem;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * B1) GET /open-items 응답 row (Phase1 프론트 mock-data.ts 매칭)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenItemListRowDto {

    /** openItemKey = bukrs-belnr-gjahr-buzei */
    private String openItemKey;
    private String bukrs;
    private String belnr;
    private String gjahr;
    private String buzei;
    /** AR|AP */
    private String type;
    private LocalDate dueDate;
    private BigDecimal amount;
    private String currency;
    /** OPEN|PARTIALLY_CLEARED|CLEARED */
    private String status;
    private Integer daysPastDue;
    private Boolean disputeFlag;
    private Boolean paymentBlock;
    private String blockReason;
    private String recommendedAction;
    /** ALLOWED|APPROVAL_REQUIRED|BLOCKED */
    private String guardrailStatus;
    private Instant lastChangeTs;
    private Long partyId;
    private String partyName;
    /** docKey = bukrs-belnr-gjahr */
    private String docKey;
}
