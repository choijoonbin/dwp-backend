package com.dwp.services.platform.hrisconfiguration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.platform.contracts.hris.generated.HrisAuditOutboxEventV1;
import org.junit.jupiter.api.Test;

class HrisAuditOutboxEventV1ConsumerContractTest {
    @Test void consumesCanonicalAuditEvidenceAbi() throws Exception {
        assertThat(HrisAuditOutboxEventV1.class.getRecordComponents())
                .extracting(component -> component.getName()).contains("scopeRevision");
    }
}
