package com.dwp.services.synapsex.dto.workbench;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Workbench 타임라인 1건. agent_activity_log 기반, occurred_at DESC 정렬.
 * Aura stage 포함. metadata_json을 FE 파싱용으로 매핑(WorkbenchTimelineMetadataDto).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkbenchTimelineItemDto {

    private Long activityId;
    private Instant occurredAt;
    /** Aura 정의 stage (SCAN, DETECT, EXECUTE, SIMULATE, ANALYZE, MATCH 등) */
    private String stage;
    private String eventType;
    private String resourceType;
    private String resourceId;
    private String actorAgentId;
    private Long actorUserId;
    private String actorDisplayName;
    /** Aura 표준: { title, reasoning, evidence, status } 구조로 매핑 */
    private WorkbenchTimelineMetadataDto metadata;
    /** 원본 metadata_json (FE에서 즉시 파싱용) */
    private Map<String, Object> metadataJson;
}
