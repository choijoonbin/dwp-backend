package com.dwp.services.mcp.dto.mcp;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;

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
    private String errorCode;
    private String errorMessage;
    private T data;

    public static <T> McpToolEnvelope<T> success(String schemaVersion, String sourceSystem, String traceId,
                                                 LocalDate effectiveFrom, LocalDate effectiveTo, T data) {
        return McpToolEnvelope.<T>builder()
                .schemaVersion(schemaVersion)
                .sourceSystem(sourceSystem)
                .evaluatedAt(Instant.now())
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .traceId(traceId)
                .success(true)
                .data(data)
                .build();
    }

    public static <T> McpToolEnvelope<T> error(String schemaVersion, String sourceSystem, String traceId,
                                               String errorCode, String errorMessage) {
        return McpToolEnvelope.<T>builder()
                .schemaVersion(schemaVersion)
                .sourceSystem(sourceSystem)
                .evaluatedAt(Instant.now())
                .traceId(traceId)
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}
