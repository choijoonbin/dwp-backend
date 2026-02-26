package com.dwp.services.mcp.dto.mcp;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class CaseContextResult {
    private Integer window10mTxnCount;
    private Integer window24hSameMerchantCount;
    private BigDecimal user30dBaselineAmount;
    private String decisionSource;
}

