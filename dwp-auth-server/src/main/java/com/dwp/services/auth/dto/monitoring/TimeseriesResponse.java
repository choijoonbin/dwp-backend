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

    private String interval; // "HOUR" | "DAY" | "1m" | "5m" | "1h" | "1d"
    private String metric; // "PV" | "UV" | "EVENT" | "API_TOTAL" | "API_ERROR" | "RPS" | "API_4XX" | "API_5XX" | "LATENCY_P50" | "LATENCY_P95" | "LATENCY_P99" | "AVAILABILITY"
    private List<String> labels; // 시간 라벨
    private List<Double> values; // 값 배열 (count 또는 rate/ms). metric=API_ERROR일 때는 에러 건수(4xx+5xx)
    /** metric=API_ERROR일 때만 존재. 각 버킷별 (에러 건수/전체 요청 수)×100 에러율(%) 배열. 프론트 value>5 등 % 기준 로직은 이 필드 사용 */
    private List<Double> valuesErrorRate;
}
