package com.dwp.services.synapsex.dto.action;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * C2) POST /actions 응답 (생성된 action + simulation)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionDetailDto {

    private Long actionId;
    private Long caseId;
    private String actionType;
    private String status;
    private JsonNode payload;
    private JsonNode simulationBefore;
    private JsonNode simulationAfter;
    private JsonNode diffJson;
    private Instant createdAt;
}
