package com.dwp.services.synapsex.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** recon_result FAIL 집계: 건수 + 최신 5건 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReconFailSummaryDto {

    private long failCount;
    private List<ReconFailItemDto> latest;
}
