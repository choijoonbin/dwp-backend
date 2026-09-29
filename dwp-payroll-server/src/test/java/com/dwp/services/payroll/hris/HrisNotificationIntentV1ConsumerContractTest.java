package com.dwp.services.payroll.hris;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.platform.contracts.hris.generated.HrisNotificationIntentV1;
import org.junit.jupiter.api.Test;

class HrisNotificationIntentV1ConsumerContractTest {
    @Test void consumesCanonicalIntentAbi() throws Exception {
        assertThat(HrisNotificationIntentV1.class.getRecordComponents())
                .extracting(component -> component.getName()).contains("tenantId");
    }
}
