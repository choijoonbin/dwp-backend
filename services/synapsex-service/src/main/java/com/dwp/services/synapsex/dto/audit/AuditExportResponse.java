package com.dwp.services.synapsex.dto.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditExportResponse {
    private String jobId;
    private String signedUrl;
    private String status;  // PENDING, READY, FAILED
}
