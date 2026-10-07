package com.dwp.services.auth.productaccess;

interface HrisAccessEvidenceProvider {

    HrisProductAccessPolicy.Evidence load(Long tenantId, Long subjectId);
}
