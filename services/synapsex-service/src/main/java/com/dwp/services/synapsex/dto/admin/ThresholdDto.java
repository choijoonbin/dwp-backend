package com.dwp.services.synapsex.dto.admin;

import com.dwp.services.synapsex.entity.RuleThreshold;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class ThresholdDto {
    private Long thresholdId;
    private Long tenantId;
    private Long profileId;
    private String policyDocId;
    private String dimension;
    private String dimensionKey;
    private String waers;
    private BigDecimal thresholdAmount;
    private Boolean requireEvidence;
    private Map<String, Object> evidenceTypes;
    private String severityOnBreach;
    private String actionOnBreach;
    private Instant createdAt;
    private Instant updatedAt;

    public static ThresholdDto from(RuleThreshold e) {
        if (e == null) return null;
        return ThresholdDto.builder()
                .thresholdId(e.getThresholdId())
                .tenantId(e.getTenantId())
                .profileId(e.getProfileId())
                .policyDocId(e.getPolicyDocId())
                .dimension(e.getDimension())
                .dimensionKey(e.getDimensionKey())
                .waers(e.getWaers())
                .thresholdAmount(e.getThresholdAmount())
                .requireEvidence(e.getRequireEvidence())
                .evidenceTypes(e.getEvidenceTypes())
                .severityOnBreach(e.getSeverityOnBreach())
                .actionOnBreach(e.getActionOnBreach())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
