package com.dwp.services.synapsex.service.audit;

import com.dwp.services.synapsex.dto.audit.AuditExportRequest;
import com.dwp.services.synapsex.dto.audit.AuditExportResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Phase 4 Optional - Audit export (signed URL or job id)
 * Initial implementation returns job id; signed URL would require S3/pre-signed URL integration.
 */
@Service
@RequiredArgsConstructor
public class AuditExportService {

    public AuditExportResponse requestExport(Long tenantId, AuditExportRequest request) {
        String jobId = "export-" + UUID.randomUUID().toString().substring(0, 8);
        return AuditExportResponse.builder()
                .jobId(jobId)
                .signedUrl(null)
                .status("PENDING")
                .build();
    }
}
