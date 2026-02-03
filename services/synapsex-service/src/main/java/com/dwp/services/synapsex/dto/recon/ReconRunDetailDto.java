package com.dwp.services.synapsex.dto.recon;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconRunDetailDto {
    private Long runId;
    private String runType;
    private Instant startedAt;
    private Instant endedAt;
    private String status;
    private JsonNode summaryJson;
    private List<ReconResultDto> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReconResultDto {
        private Long resultId;
        private String resourceType;
        private String resourceKey;
        private String status;
        private JsonNode detailJson;
    }
}
