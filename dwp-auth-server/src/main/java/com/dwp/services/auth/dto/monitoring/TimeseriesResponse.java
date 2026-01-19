package com.dwp.services.auth.dto.monitoring;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 시계열 데이터 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimeseriesResponse {
    
    private String interval; // "HOUR" | "DAY"
    private String metric; // "PV" | "UV" | "EVENT" | "API_TOTAL" | "API_ERROR"
    private List<String> labels; // 시간 라벨 (예: ["2026-01-01", "2026-01-02", ...])
    private List<Long> values; // 값 배열
}
