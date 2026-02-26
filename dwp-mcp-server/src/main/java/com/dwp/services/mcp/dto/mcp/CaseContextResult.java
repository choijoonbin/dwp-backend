package com.dwp.services.mcp.dto.mcp;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class CaseContextResult {
    // legacy fields
    private Integer window10mTxnCount;
    private Integer window24hSameMerchantCount;
    private BigDecimal user30dBaselineAmount;
    private String decisionSource;

    // v2 required fields
    private Integer window24hTxnCount;
    private Integer window30dTxnCount;
    private BigDecimal avgAmount30d;
    private BigDecimal peerGroupPercentile;
    private String decisionCode;
    private List<String> evidenceRefs;
}
