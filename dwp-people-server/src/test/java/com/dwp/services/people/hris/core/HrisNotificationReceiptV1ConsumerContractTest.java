package com.dwp.services.people.hris.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.platform.contracts.hris.generated.HrisNotificationReceiptV1;
import org.junit.jupiter.api.Test;

class HrisNotificationReceiptV1ConsumerContractTest {
    @Test void consumesCanonicalReceiptAbi() throws Exception {
        assertThat(HrisNotificationReceiptV1.class.getRecordComponents())
                .extracting(component -> component.getName()).contains("receiptSequence");
    }
}
