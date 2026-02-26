package com.dwp.services.mcp.dto.mcp;

import lombok.Data;

import java.time.Instant;

@Data
public class BusinessCalendarRequest {
    private Instant occurredAt;
    private Long userId;
}
