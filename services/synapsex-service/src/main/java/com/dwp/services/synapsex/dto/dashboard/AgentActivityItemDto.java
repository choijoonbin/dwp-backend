package com.dwp.services.synapsex.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * GET /api/synapse/dashboard/agent-activity 응답 항목
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentActivityItemDto {

    private Instant ts;
    private String level;   // INFO | WARN | ERROR
    private String stage;   // SCAN | DETECT | EXECUTE | SIMULATE | ANALYZE | MATCH
    private String message;
    private String caseId;   // CS-2026-0001 형식
    private String actionId; // AC-2026-0321 형식
    private String resourceType;  // CASE | ACTION | INTEGRATION
    private String resourceId;
    private String traceId;
    private Links links;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Links {
        private String casePath;
        private String auditPath;
    }
}
