package com.dwp.services.synapsex.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GET /api/synapse/dashboard/top-risk-drivers 응답 래퍼
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopRiskDriversResponseDto {

    private String range;
    private List<TopRiskDriverDto> items;
}
