package com.dwp.services.synapsex.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * GET /api/synapse/dashboard/action-required 응답 항목
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionRequiredDto {

    private Long actionId;
    private Long caseId;
    private String severity;
    private String title;
    private String ctaLabel;
    private Instant createdAt;

    /** FE 계약 호환 */
    private String id;
    private String description;
    private String riskLevel;
    private String caseNumber;

    /** primary action id (actionId 별칭) */
    private Long primaryActionId;
    /** 사유 요약 */
    private String reasonShort;
    /** 클릭 시 review 경로 */
    private ReviewLinks links;

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ReviewLinks {
        private String reviewPath;
    }
}
