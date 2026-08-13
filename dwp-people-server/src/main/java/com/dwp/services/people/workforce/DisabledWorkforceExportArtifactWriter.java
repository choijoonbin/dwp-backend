package com.dwp.services.people.workforce;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "dwp.people.exports.execution-enabled",
        havingValue = "false",
        matchIfMissing = true)
public class DisabledWorkforceExportArtifactWriter implements WorkforceExportArtifactWriter {

    @Override
    public WorkforceExportDtos.ArtifactEvidence write(WorkforceExportRepository.RequestRow request) {
        throw new IllegalStateException(
                "A KMS-backed workforce export artifact writer has not been configured.");
    }

    @Override
    public void discard(WorkforceExportDtos.ArtifactEvidence artifact) {
        throw new IllegalStateException(
                "A workforce export artifact store is required before retention cleanup can run.");
    }
}
