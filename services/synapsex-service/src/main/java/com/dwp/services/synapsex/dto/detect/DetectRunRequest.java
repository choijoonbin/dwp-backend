package com.dwp.services.synapsex.dto.detect;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

/**
 * POST /api/synapse/admin/detect/run 요청 본문.
 * windowMinutes 또는 from/to 지정.
 */
@Data
public class DetectRunRequest {

    /** 최근 N분 윈도우 (from/to 생략 시 사용, 기본 15) */
    @JsonProperty("windowMinutes")
    private Integer windowMinutes;

    /** backfill: 윈도우 시작 (ISO 8601) */
    @JsonProperty("from")
    private Instant from;

    /** backfill: 윈도우 종료 (ISO 8601) */
    @JsonProperty("to")
    private Instant to;
}
