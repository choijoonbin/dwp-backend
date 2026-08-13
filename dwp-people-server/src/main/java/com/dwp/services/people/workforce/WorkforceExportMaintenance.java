package com.dwp.services.people.workforce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WorkforceExportMaintenance {

    private static final Logger log = LoggerFactory.getLogger(WorkforceExportMaintenance.class);

    private final WorkforceExportRepository repository;
    private final WorkforceExportArtifactWriter artifactWriter;
    private final WorkforceExportService service;
    private final int batchSize;

    public WorkforceExportMaintenance(
            WorkforceExportRepository repository,
            WorkforceExportArtifactWriter artifactWriter,
            WorkforceExportService service,
            @Value("${dwp.people.exports.expiry-scan-batch-size:100}") int batchSize) {
        this.repository = repository;
        this.artifactWriter = artifactWriter;
        this.service = service;
        this.batchSize = Math.min(500, Math.max(1, batchSize));
    }

    @Scheduled(fixedDelayString = "${dwp.people.exports.expiry-scan-interval-ms:60000}")
    public void expireArtifacts() {
        for (WorkforceExportRepository.RequestRow row : repository.dueArtifacts(batchSize)) {
            try {
                artifactWriter.discard(new WorkforceExportDtos.ArtifactEvidence(
                        row.artifactReference(), row.artifactSha256(),
                        row.artifactSizeBytes(), row.artifactExpiresAt()));
                service.expireArtifact(row);
            } catch (RuntimeException exception) {
                // Keep the governed reference intact so the next scan can retry cleanup.
                log.error("Workforce export artifact expiry failed for request {}",
                        row.requestId(), exception);
            }
        }
    }
}
