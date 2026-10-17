package com.dwp.services.people.hris.employeeservice.certificate;

/**
 * Shared render/seal/object boundary. Stage A deliberately has no production
 * success provider; implementations must return immutable metadata only.
 */
public interface CertificateArtifactPort {

    CertificateDtos.ArtifactResult issue(CertificateDtos.ArtifactRequest request);
}
