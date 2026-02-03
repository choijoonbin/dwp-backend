package com.dwp.services.synapsex.dto.case_;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * A2) GET /cases/{caseId} 3-panel 응답
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseDetailDto {

    private EvidencePanelDto evidence;
    private ReasoningPanelDto reasoning;
    private ActionPanelDto action;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidencePanelDto {
        private DocumentOrOpenItemDto documentOrOpenItem;
        private ReversalChainSummaryDto reversalChainSummary;
        private List<Long> relatedPartyIds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentOrOpenItemDto {
        private String type;  // DOCUMENT | OPEN_ITEM
        private String docKey;
        private Object headerSummary;
        private List<Object> items;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReversalChainSummaryDto {
        private List<String> nodeDocKeys;
        private int edgeCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReasoningPanelDto {
        private BigDecimal score;
        private String reasonText;
        private JsonNode evidenceJson;
        private JsonNode ragRefsJson;
        private ConfidenceBreakdownDto confidenceBreakdown;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfidenceBreakdownDto {
        private Double anomalyScore;
        private Double patternMatch;
        private Double ruleCompliance;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionPanelDto {
        private List<String> availableActionTypes;
        private List<ActionSummaryDto> actions;
        private LineageLinkParamsDto lineageLinkParams;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionSummaryDto {
        private Long actionId;
        private String actionType;
        private String status;
        private String createdAt;
        private String executedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineageLinkParamsDto {
        private Long caseId;
        private String docKey;
        private Long rawEventId;
        private Long partyId;
    }
}
