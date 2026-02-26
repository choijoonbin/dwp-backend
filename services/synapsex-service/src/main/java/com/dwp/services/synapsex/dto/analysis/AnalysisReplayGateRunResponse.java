package com.dwp.services.synapsex.dto.analysis;

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
public class AnalysisReplayGateRunResponse {
    private Long id;
    private String runKey;
    private Boolean gatePassed;
    private JsonNode resultJson;
    private Instant createdAt;
}
