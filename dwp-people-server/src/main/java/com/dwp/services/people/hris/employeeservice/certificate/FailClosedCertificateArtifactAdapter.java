package com.dwp.services.people.hris.employeeservice.certificate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Production binding until a separately accepted shared artifact provider exists. */
@Component
@ConditionalOnProperty(prefix = Hrm009RolloutGate.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = false)
final class FailClosedCertificateArtifactAdapter implements CertificateArtifactPort {

    @Override
    public CertificateDtos.ArtifactResult issue(
            CertificateDtos.ArtifactRequest ignored) {
        throw CertificateException.unavailable(
                "No accepted production certificate artifact provider is bound.");
    }
}
