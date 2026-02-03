package com.dwp.services.synapsex.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GET /api/synapse/dashboard/agent-activity 응답
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentActivityResponseDto {

    private String range;
    private List<AgentActivityItemDto> items;
}
