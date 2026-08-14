package com.dwp.services.people.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkdayReferenceMapperTest {

    private final WorkdayReferenceMapper mapper = new WorkdayReferenceMapper(new ObjectMapper());

    @Test
    void mapsSyntheticWorkforceScenariosToCanonicalProjection() {
        HrisModels.WorkforceBatch batch = mapper.mapSyntheticFixture();

        assertThat(batch.synthetic()).isTrue();
        assertThat(batch.sourceType()).isEqualTo("WORKDAY");
        assertThat(batch.workers()).hasSize(3);
        assertThat(batch.workers())
                .extracting(HrisModels.WorkerRecord::workerType)
                .containsExactly("EMPLOYEE", "EMPLOYEE", "CONTINGENT");
        assertThat(batch.workers().get(1).assignments())
                .hasSize(2)
                .extracting(HrisModels.Assignment::changeReasonCode)
                .containsExactly("HIRE", "INTERNAL_TRANSFER");
        assertThat(batch.workers())
                .extracting(HrisModels.WorkerRecord::workEmail)
                .allMatch(email -> email.endsWith("@sk.com"));
    }
}
