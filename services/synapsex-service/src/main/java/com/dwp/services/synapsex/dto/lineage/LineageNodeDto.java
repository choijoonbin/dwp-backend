package com.dwp.services.synapsex.dto.lineage;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Lineage 그래프 노드 1건. Source -> Agent -> Case -> Action 계층용.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LineageNodeDto {

    /** 노드 고유 ID (예: source-{docKey}, agent-{activityId}, case-{caseId}, action-{actionId}) */
    private String id;

    /** SOURCE | AGENT | CASE | ACTION */
    private String type;

    /** 표시용 라벨 */
    private String label;

    /** 원본 식별자 (rawEventId, activityId, caseId, actionId) */
    private String refId;

    /** 정렬/연결용 시각 (nullable) */
    private Instant occurredAt;

    /** type별 상세 (예: stage, eventType, severity) */
    private Map<String, Object> payload;
}
