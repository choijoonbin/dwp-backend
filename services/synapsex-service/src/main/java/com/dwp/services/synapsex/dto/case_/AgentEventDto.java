package com.dwp.services.synapsex.dto.case_;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AgentEventDto {
    @JsonProperty("event_type")
    private String eventType;
    private String node;
    private String tool;
    @JsonProperty("decision_code")
    private String decisionCode;
    @JsonProperty("input_hash")
    private String inputHash;
    @JsonProperty("output_ref")
    private String outputRef;
    @JsonProperty("evidence_ids")
    private List<String> evidenceIds;
    private String timestamp;
    @JsonProperty("run_id")
    private String runId;
    @JsonProperty("case_id")
    private String caseId;
    @JsonProperty("latency_ms")
    private Long latencyMs;
    @JsonProperty("summary_message")
    private String summaryMessage;
    private String message;
    @JsonProperty("error_code")
    private String errorCode;
    @JsonProperty("error_message")
    private String errorMessage;
    private Integer count;
}
