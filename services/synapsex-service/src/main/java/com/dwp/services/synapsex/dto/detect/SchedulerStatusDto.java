package com.dwp.services.synapsex.dto.detect;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * GET /api/synapse/admin/detect/scheduler/status 응답.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SchedulerStatusDto {

    private boolean enabled;
    private String scheduleType;  // 'fixedDelay' | 'cron'
    private Integer intervalMinutes;
    private String cronExpression;
    private Long lastRunId;
    private Instant lastSuccessAt;
    private Instant lastFailAt;
    private boolean running;
    private Long runningRunId;
    private Instant runningSince;
    private Instant nextPlannedAt;
}
