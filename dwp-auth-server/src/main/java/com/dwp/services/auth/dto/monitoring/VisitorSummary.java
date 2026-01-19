package com.dwp.services.auth.dto.monitoring;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 방문자 요약 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisitorSummary {
    
    private String visitorId;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private Long pageViewCount;
    private Long eventCount;
    private String lastPath;
    private String lastDevice;
    private Long lastUserId;
}
