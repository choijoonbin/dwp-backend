package com.dwp.services.synapsex.dto.lineage;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * D1) GET /api/synapse/lineage 응답
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LineageResponseDto {

    /** journeyNodes: ["SAP Raw Event","Ingestion & Normalization","AI Feature Extraction","Anomaly Scoring","Case Created","Action Executed"] */
    private List<String> journeyNodes;

    /** timestamps per node (nodeId -> ISO timestamp) */
    private Map<String, String> timestamps;

    /** evidence panel */
    private EvidencePanelDto evidencePanel;

    /** time-travel: asOf 요청 시 */
    private AsOfSnapshotDto asOfSnapshot;

    /** time-travel: 현재 스냅샷 */
    private AsOfSnapshotDto currentSnapshot;

    /** time-travel: 히스토리 테이블 없을 때 true */
    private Boolean timeTravelDegraded;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidencePanelDto {
        /** ingestion errors summary for related raw_event_id */
        private IngestionErrorsSummaryDto ingestionErrors;

        /** case.evidence_json + case.rag_refs_json (caseId 제공 시) */
        private CaseEvidenceDto caseEvidence;

        /** statistical evidence (mock if not available) */
        private StatisticalEvidenceDto statisticalEvidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IngestionErrorsSummaryDto {
        private Long rawEventId;
        private long errorCount;
        private List<IngestionErrorItemDto> errors;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IngestionErrorItemDto {
        private Long id;
        private String errorCode;
        private String errorDetail;
        private String createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CaseEvidenceDto {
        private Long caseId;
        private JsonNode evidenceJson;
        private JsonNode ragRefsJson;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatisticalEvidenceDto {
        private String description;
        private Map<String, Object> metrics;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AsOfSnapshotDto {
        private String asOfTimestamp;
        private Object partySnapshot;
        private Object docSnapshot;
    }
}
