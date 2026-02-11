package com.dwp.services.synapsex.dto.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Aura Phase3: 202 응답 — accepted, runId, streamPath
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuraPhase3TriggerResponse {

    private Boolean accepted;
    private UUID runId;
    /** FE 스트림 URL 예: /aura/cases/{caseId}/analysis/stream?runId=... */
    private String streamPath;
}
