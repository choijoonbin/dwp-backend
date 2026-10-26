package com.dwp.services.people.hris.separation.hrm013;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Hrm013SeparationReconciliationInProcessContractTest {
    @Test
    void reconciliationIsAnInProcessPortAndBusinessCommandCarriesNoAuthorityRevision() {
        assertThat(SeparationReconciliationPort.class.isAssignableFrom(
                Hrm013OwnerService.class)).isTrue();
        assertThat(Arrays.stream(SeparationReconciliationPort.Command.class
                        .getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("tenantGrantRevision", "authorizationRevision")
                .contains("consumerEventId", "sourcePayloadSha256", "sourceWorkloadId");
        assertThat(Arrays.stream(SeparationReconciliationPort.class.getDeclaredMethods()))
                .allMatch(method -> !method.getName().toLowerCase().contains("http"));
    }
}
