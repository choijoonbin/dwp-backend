package com.dwp.services.people.workforce;

public interface WorkforceExportArtifactWriter {

    WorkforceExportDtos.ArtifactEvidence write(WorkforceExportRepository.RequestRow request);

    /**
     * Removes a governed artifact. Implementations must make this operation idempotent because
     * cancellation, retention cleanup, and transaction recovery can invoke it more than once.
     */
    void discard(WorkforceExportDtos.ArtifactEvidence artifact);
}
