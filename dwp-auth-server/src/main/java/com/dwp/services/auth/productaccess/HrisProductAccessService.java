package com.dwp.services.auth.productaccess;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class HrisProductAccessService {

    private final HrisAccessEvidenceProvider evidenceProvider;
    private final HrisProductAccessPolicy policy = new HrisProductAccessPolicy();

    public HrisProductAccessService(HrisAccessEvidenceProvider evidenceProvider) {
        this.evidenceProvider = evidenceProvider;
    }

    @Transactional(readOnly = true)
    public HrisProductAccessDtos.AccessSnapshot snapshot(Long tenantId, Long subjectId) {
        return policy.evaluate(
                tenantId, subjectId, evidenceProvider.load(tenantId, subjectId),
                OffsetDateTime.now(ZoneOffset.UTC));
    }
}
