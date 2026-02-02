package com.dwp.services.synapsex.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
public class UpsertThresholdRequest {
    private Long thresholdId;
    @NotNull
    private Long profileId;
    private String policyDocId;
    @NotBlank
    private String dimension;
    @NotBlank
    private String dimensionKey;
    private String waers = "KRW";
    @NotNull
    private BigDecimal thresholdAmount;
    private Boolean requireEvidence = false;
    private Map<String, Object> evidenceTypes;
    private String severityOnBreach = "MEDIUM";
    private String actionOnBreach = "FLAG_FOR_REVIEW";
}
