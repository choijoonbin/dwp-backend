package com.dwp.services.mcp.dto.mcp;

import lombok.Data;

import java.time.Instant;

@Data
public class PolicyLookupRequest {
    private String article;
    private String clause;
    private Instant effectiveAt;
}
