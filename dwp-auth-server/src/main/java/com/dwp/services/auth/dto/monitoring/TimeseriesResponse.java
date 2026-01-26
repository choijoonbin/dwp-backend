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
    private String metric; // "PV" | "UV" | "EVENT" | "API_TOTAL" | "API_ERROR" | "RPS" | "API_4XX" | "API_5XX" | "LATENCY_P50" | "LATENCY_P95" | "LATENCY_P99" | "AVAILABILITY"
    private List<String> labels; // 시간 라벨
    private List<Double> values; // 값 배열 (count 또는 rate/ms/%)
}
