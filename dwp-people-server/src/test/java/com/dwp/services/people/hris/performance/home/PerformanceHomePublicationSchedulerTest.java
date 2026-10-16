package com.dwp.services.people.hris.performance.home;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PerformanceHomePublicationSchedulerTest {

    @Test
    void atomicallyAcknowledgesOnlyTheExactPublishedSourceVersion() {
        PerformanceHomePublicationCandidates candidates =
                mock(PerformanceHomePublicationCandidates.class);
        UUID candidateId = UUID.fromString("00000000-0000-0000-0000-000000000911");
        UUID correlationId = UUID.fromString("00000000-0000-0000-0000-000000000912");
        when(candidates.lockNext()).thenReturn(Optional.of(
                new PerformanceHomePublicationCandidates.Candidate(
                        candidateId, 9, correlationId,
                        PerformanceHomeTestFixtures.authority())));
        PerformanceHomeProjectionPort projections = mock(PerformanceHomeProjectionPort.class);
        when(projections.load(PerformanceHomeTestFixtures.authority()))
                .thenReturn(PerformanceHomeTestFixtures.snapshot());
        var publisher = new PerformanceHomeEventPublisher(
                projections, PerformanceHomeTestFixtures.materializer(),
                mock(DomainEventRecorder.class), new DomainEventContractRegistry(),
                new ObjectMapper().findAndRegisterModules(), true);

        new PerformanceHomePublicationScheduler(candidates, publisher).publishNext();

        ArgumentCaptor<PerformanceHomePublicationCandidates.PublicationReceipt> receipt =
                ArgumentCaptor.forClass(
                        PerformanceHomePublicationCandidates.PublicationReceipt.class);
        verify(candidates).markPublished(receipt.capture());
        assertThat(receipt.getValue().candidateId()).isEqualTo(candidateId);
        assertThat(receipt.getValue().sourceVersion()).isEqualTo(9);
        assertThat(receipt.getValue().payloadDigest()).matches("^[0-9a-f]{64}$");
    }
}
