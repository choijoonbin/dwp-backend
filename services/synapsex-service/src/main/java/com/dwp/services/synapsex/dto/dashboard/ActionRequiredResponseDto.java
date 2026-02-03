package com.dwp.services.synapsex.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GET /api/synapse/dashboard/action-required 응답 래퍼
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionRequiredResponseDto {

    private List<ActionRequiredDto> items;
}
