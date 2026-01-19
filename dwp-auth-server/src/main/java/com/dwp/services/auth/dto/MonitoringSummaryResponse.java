package com.dwp.services.auth.dto;

import lombok.*;

/**
 * 모니터링 요약 응답
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonitoringSummaryResponse {
    private Long pv;
    private Long uv;
    private Long events;
    private Double apiErrorRate;
    private Double pvDeltaPercent;
    private Double uvDeltaPercent;
    private Double eventDeltaPercent;
    private Double apiErrorDeltaPercent;
}
