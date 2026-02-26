package com.dwp.services.mcp.dto.mcp;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

import java.time.Instant;

@Data
public class BusinessCalendarRequest {
    private Instant occurredAt;
    @Setter(AccessLevel.NONE)
    @JsonAlias("user_id")
    private Long userId;

    @JsonSetter("userId")
    public void setUserIdFlexible(Object raw) {
        this.userId = parseLongOrNull(raw);
    }

    @JsonSetter("user_id")
    public void setUserIdSnakeFlexible(Object raw) {
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
