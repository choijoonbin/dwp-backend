package com.dwp.services.synapsex.dto.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditExportRequest {
    private Instant from;
    private Instant to;
    private String category;
    private String format;  // CSV, JSON
}
