package com.dwp.services.synapsex.dto.lineage;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 전표 기준 계보 그래프. FE 그래프 렌더링용 Source → Agent → Case → Action 구조.
 * GET /api/v1/synapse/lineage/{resourceKey} 응답.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LineageGraphDto {

    /** docKey (resourceKey = bukrs-belnr-gjahr) */
    private String resourceKey;

    /** 노드 목록 (SOURCE, AGENT, CASE, ACTION, 시간순 권장) */
    private List<LineageNodeDto> nodes;

    /** 엣지 목록 (fromId → toId) */
    private List<LineageEdgeDto> edges;
}
