package com.dwp.services.mcp.dto.mcp;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class McpToolEnvelope<T> {
    private String schemaVersion;
    private String sourceSystem;
    private Instant evaluatedAt;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String traceId;
    private Boolean success;
    private String decisionCode;
    private String errorCode;
    private String errorMessage;
    private List<String> missingFields;
    private List<String> evidenceRefs;
    private Meta meta;
    private T data;

    @Data
    @Builder
    public static class Meta {
        private Long latencyMs;
    }

    public static <T> McpToolEnvelope<T> success(String schemaVersion, String sourceSystem, String traceId,
                                                 LocalDate effectiveFrom, LocalDate effectiveTo, T data) {
        return success(schemaVersion, sourceSystem, traceId, effectiveFrom, effectiveTo, "OK", List.of(), null, data);
    }

    public static <T> McpToolEnvelope<T> success(String schemaVersion, String sourceSystem, String traceId,
                                                 LocalDate effectiveFrom, LocalDate effectiveTo,
                                                 String decisionCode, List<String> evidenceRefs, Long latencyMs, T data) {
        return McpToolEnvelope.<T>builder()
                .schemaVersion(schemaVersion)
                .sourceSystem(sourceSystem)
                .evaluatedAt(Instant.now())
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .traceId(traceId)
                .success(true)
                .decisionCode(decisionCode)
                .evidenceRefs(evidenceRefs != null ? evidenceRefs : List.of())
                .meta(Meta.builder().latencyMs(latencyMs).build())
                .data(data)
                .build();
    }

    public static <T> McpToolEnvelope<T> error(String schemaVersion, String sourceSystem, String traceId,
                                               String errorCode, String errorMessage) {
        return error(schemaVersion, sourceSystem, traceId, errorCode, errorMessage, errorCode, List.of(), null);
    }

    public static <T> McpToolEnvelope<T> error(String schemaVersion, String sourceSystem, String traceId,
                                               String errorCode, String errorMessage,
                                               String decisionCode, List<String> evidenceRefs, Long latencyMs) {
        return McpToolEnvelope.<T>builder()
                .schemaVersion(schemaVersion)
                .sourceSystem(sourceSystem)
                .evaluatedAt(Instant.now())
                .traceId(traceId)
                .success(false)
                .decisionCode(decisionCode)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .missingFields(List.of())
                .evidenceRefs(evidenceRefs != null ? evidenceRefs : List.of())
                .meta(Meta.builder().latencyMs(latencyMs).build())
                .build();
    }
}
