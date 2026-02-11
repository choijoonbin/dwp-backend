package com.dwp.services.synapsex.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Phase 6: fi_doc_header 시나리오 전표 통계 (위반/정상 대조군).
 * belnr 접두어: DEMO = 위반, NORM = 정상.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FiDocScenarioStatsDto {

    private long total;
    /** 위반 시나리오 건수 (belnr LIKE 'DEMO%') */
    private long violationCount;
    /** 정상 시나리오 건수 (belnr LIKE 'NORM%') */
    private long normalCount;
}
