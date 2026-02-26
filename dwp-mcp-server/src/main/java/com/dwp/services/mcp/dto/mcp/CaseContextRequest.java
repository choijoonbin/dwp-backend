package com.dwp.services.mcp.dto.mcp;

import lombok.Data;

import java.time.Instant;

@Data
public class CaseContextRequest {
    private Instant occurredAt;
    private Long userId;
    private Long caseId;
    private String merchantName;
}

