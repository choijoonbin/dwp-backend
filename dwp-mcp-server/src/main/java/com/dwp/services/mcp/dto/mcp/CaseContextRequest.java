package com.dwp.services.mcp.dto.mcp;

import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class CaseContextRequest {
    private Long caseId;
    private String docKey;
    private Instant occurredAt;
    private Long userId;
    private String mccCode;
    private BigDecimal amount;
    private String merchantName;

    @JsonSetter("userId")
    public void setUserIdFlexible(Object raw) {
        this.userId = parseLongOrNull(raw);
    }

    private Long parseLongOrNull(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return n.longValue();
        String s = String.valueOf(raw).trim();
        if (s.isBlank()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
