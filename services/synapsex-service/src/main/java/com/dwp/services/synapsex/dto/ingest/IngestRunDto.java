package com.dwp.services.synapsex.dto.ingest;

import com.dwp.services.synapsex.entity.IngestRun;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class IngestRunDto {

    private Long runId;
    private Long tenantId;
    private String batchId;
    private Instant windowFrom;
    private Instant windowTo;
    private Integer recordCount;
    private String status;
    private String errorMessage;
    private Instant startedAt;
    private Instant completedAt;

    public static IngestRunDto from(IngestRun run) {
        return IngestRunDto.builder()
                .runId(run.getRunId())
                .tenantId(run.getTenantId())
                .batchId(run.getBatchId())
                .windowFrom(run.getWindowFrom())
                .windowTo(run.getWindowTo())
                .recordCount(run.getRecordCount())
                .status(run.getStatus())
                .errorMessage(run.getErrorMessage())
                .startedAt(run.getStartedAt())
                .completedAt(run.getCompletedAt())
                .build();
    }
}
