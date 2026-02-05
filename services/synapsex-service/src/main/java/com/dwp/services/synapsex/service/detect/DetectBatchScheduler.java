package com.dwp.services.synapsex.service.detect;

import com.dwp.services.synapsex.config.DetectBatchConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Phase B: Detect Run 스케줄러 (15분 또는 60분 윈도우)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "detect.batch.enabled", havingValue = "true")
public class DetectBatchScheduler {

    private final DetectBatchConfig config;
    private final DetectBatchService detectBatchService;

    @Scheduled(cron = "${detect.batch.cron:0 */15 * * * *}")
    public void runDetectBatch() {
        Instant now = Instant.now();
        Instant windowTo = now;
        Instant windowFrom = now.minus(15, ChronoUnit.MINUTES);

        for (Long tenantId : config.getTenantIds()) {
            try {
                var run = detectBatchService.runDetectBatch(tenantId, windowFrom, windowTo);
                if (run != null) {
                    log.info("Detect batch completed tenant={} runId={} status={}", tenantId, run.getRunId(), run.getStatus());
                }
            } catch (Exception e) {
                log.error("Detect batch failed tenant={}", tenantId, e);
            }
        }
    }
}
