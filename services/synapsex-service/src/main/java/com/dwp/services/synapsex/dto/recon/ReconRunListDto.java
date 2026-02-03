package com.dwp.services.synapsex.dto.recon;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconRunListDto {
    private Long runId;
    private String runType;
    private Instant startedAt;
    private Instant endedAt;
    private String status;
    private JsonNode summaryJson;
}
