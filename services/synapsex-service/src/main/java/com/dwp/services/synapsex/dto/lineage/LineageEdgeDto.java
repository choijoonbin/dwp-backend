package com.dwp.services.synapsex.dto.lineage;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lineage 그래프 엣지. fromId → toId (노드 id 참조).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LineageEdgeDto {

    private String fromId;
    private String toId;
}
