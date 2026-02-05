package com.dwp.services.synapsex.dto.detect;

import com.dwp.services.synapsex.entity.DetectRun;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DetectRunDto {

    private Long runId;
    private Long tenantId;
    private Instant windowFrom;
    private Instant windowTo;
    private String status;
    private JsonNode countsJson;
    private String errorMessage;
    private Instant startedAt;
    private Instant completedAt;

    /** SKIPPED 시: 현재 실행 중인 run ID */
    private Long runningRunId;
    /** SKIPPED 시: 해당 run 시작 시각 */
    private Instant runningSince;
    /** SKIPPED 시: 스킵 사유 */
    private String skipReason;

    public static DetectRunDto from(DetectRun run) {
        return DetectRunDto.builder()
                .runId(run.getRunId())
                .tenantId(run.getTenantId())
                .windowFrom(run.getWindowFrom())
                .windowTo(run.getWindowTo())
                .status(run.getStatus())
                .countsJson(run.getCountsJson())
                .errorMessage(run.getErrorMessage())
                .startedAt(run.getStartedAt())
                .completedAt(run.getCompletedAt())
                .build();
    }
}
