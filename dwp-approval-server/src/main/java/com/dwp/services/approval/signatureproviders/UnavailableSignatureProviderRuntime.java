package com.dwp.services.approval.signatureproviders;

import static com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.*;
import java.util.List;

final class UnavailableSignatureProviderRuntime implements SignatureProviderRuntime {
    @Override public List<RuntimeProvider> current(long tenantId, String resourceSetKey) { return List.of(); }
    @Override public List<ProbeResult> probe(long tenantId, String resourceSetKey, ProbeInput input) { throw SignatureProviderErrors.unavailable(); }
    @Override public Kms probeKms(long tenantId, String resourceSetKey, KmsProbeInput input) { throw SignatureProviderErrors.unavailable(); }
    @Override public Worm inspectWorm(long tenantId, String resourceSetKey, WormInspectionInput input) { throw SignatureProviderErrors.unavailable(); }
    @Override public ExternalTransition handover(long tenantId, String resourceSetKey, ExternalRequest current, String key) { throw SignatureProviderErrors.unavailable(); }
    @Override public ExternalTransition refresh(long tenantId, String resourceSetKey, ExternalRequest current, String key) { throw SignatureProviderErrors.unavailable(); }
    @Override public ExternalTransition cancel(long tenantId, String resourceSetKey, ExternalRequest current, String key) { throw SignatureProviderErrors.unavailable(); }
}
