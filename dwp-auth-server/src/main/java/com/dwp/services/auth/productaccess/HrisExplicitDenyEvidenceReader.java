package com.dwp.services.auth.productaccess;

import java.util.Set;

interface HrisExplicitDenyEvidenceReader {

    Set<String> load(Long tenantId, Long subjectId);
}
