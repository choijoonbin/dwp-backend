package com.dwp.services.synapsex.dto.openitem;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * B1) GET /open-items 응답 row
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenItemListRowDto {

    private String bukrs;
    private String belnr;
    private String gjahr;
    private String buzei;
    private String itemType;
    private LocalDate dueDate;
    private BigDecimal openAmount;
    private String currency;
    private Boolean cleared;
    private Boolean paymentBlock;
    private Boolean disputeFlag;
    private Instant lastChangeTs;
    private Long partyId;
    private String docLinkKey;  // "{bukrs}-{belnr}-{gjahr}"
}
