package com.dwp.services.synapsex.dto.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagDocumentDetailDto {
    private Long docId;
    private String title;
    private String sourceType;
    private String docType;
    private String s3Key;
    private String url;
    /** 로컬 절대 경로 (source_type=UPLOAD 시) */
    private String filePath;
    private String checksum;
    private String status;
    /** 문서 버전 */
    private String version;
    /** 효력 시작일 */
    private LocalDate effectiveFrom;
    /** 효력 종료일 */
    private LocalDate effectiveTo;
    /** 청킹 전략 */
    private String chunkingStrategy;
    /** 품질 게이트 통과 여부 */
    private Boolean qualityGatePassed;
    /** 최신 품질 리포트(JSON 원문) */
    private JsonNode qualityReport;
    private Instant createdAt;
    private Instant updatedAt;
    private List<RagChunkDto> chunks;

    /** FE 호환용 snake_case alias */
    @JsonProperty("quality_report")
    public JsonNode getQualityReportSnake() {
        return qualityReport;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RagChunkDto {
        private Long chunkId;
        private Integer chunkIndex;
        private Integer pageNo;
        private String chunkText;
        private String embeddingId;
        private Map<String, Object> metadataJson;
    }
}
