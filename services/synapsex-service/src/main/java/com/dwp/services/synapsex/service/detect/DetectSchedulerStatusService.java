package com.dwp.services.synapsex.service.detect;

import com.dwp.services.synapsex.config.DetectBatchConfig;
import com.dwp.services.synapsex.dto.detect.SchedulerStatusDto;
import com.dwp.services.synapsex.entity.DetectRun;
import com.dwp.services.synapsex.repository.DetectRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Detect Scheduler 상태 조회 (detect_run 기반).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DetectSchedulerStatusService {

    private final DetectBatchConfig config;
    private final DetectRunRepository detectRunRepository;

    public SchedulerStatusDto getStatus(Long tenantId) {
        List<DetectRun> recent = detectRunRepository.findByTenantIdOrderByStartedAtDesc(tenantId, PageRequest.of(0, 50)).getContent();

        Long lastRunId = null;
        Instant lastSuccessAt = null;
        Instant lastFailAt = null;
        DetectRun runningRun = detectRunRepository.findTopByTenantIdAndStatusOrderByStartedAtDesc(tenantId, "STARTED").orElse(null);

        for (DetectRun r : recent) {
            if (lastRunId == null) lastRunId = r.getRunId();
            if ("COMPLETED".equals(r.getStatus()) && lastSuccessAt == null) lastSuccessAt = r.getCompletedAt();
            if ("FAILED".equals(r.getStatus()) && lastFailAt == null) lastFailAt = r.getCompletedAt();
            if (lastSuccessAt != null && lastFailAt != null) break;
        }

        Instant nextPlannedAt = null;
        if (config.isEnabled() && config.getIntervalMinutes() != null) {
            Instant base = runningRun != null ? runningRun.getStartedAt() : (recent.isEmpty() ? Instant.now() : recent.get(0).getStartedAt());
            nextPlannedAt = base.plusSeconds((long) config.getIntervalMinutes() * 60);
        }

        return SchedulerStatusDto.builder()
                .enabled(config.isEnabled())
                .scheduleType("cron")
                .intervalMinutes(config.getIntervalMinutes())
                .cronExpression(config.getCron())
                .lastRunId(lastRunId)
                .lastSuccessAt(lastSuccessAt)
                .lastFailAt(lastFailAt)
                .running(runningRun != null)
                .runningRunId(runningRun != null ? runningRun.getRunId() : null)
                .runningSince(runningRun != null ? runningRun.getStartedAt() : null)
                .nextPlannedAt(nextPlannedAt)
                .build();
    }
}
