package com.dwp.services.auth.productaccess;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HrisProductAccessServiceTest {

    @Test
    void keepsTenantAndSubjectBoundToTheEvidenceLookup() {
        HrisAccessEvidenceProvider evidence = mock(HrisAccessEvidenceProvider.class);
        when(evidence.load(7L, 11L)).thenReturn(new HrisProductAccessPolicy.Evidence(
                Set.of("APP.HCM:VIEW"), Set.of(), List.of()));
        HrisProductAccessService service = new HrisProductAccessService(evidence);

        var result = service.snapshot(7L, 11L);

        assertThat(result.tenantId()).isEqualTo(7L);
        assertThat(result.subjectId()).isEqualTo(11L);
        verify(evidence).load(7L, 11L);
    }
}
