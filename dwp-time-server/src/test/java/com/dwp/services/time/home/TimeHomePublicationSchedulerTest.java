package com.dwp.services.time.home;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TimeHomePublicationSchedulerTest {

    @Test
    void producerActivationAlsoEnablesItsSchedulerInfrastructure() {
        assertThat(TimeHomeProducerConfiguration.class.getAnnotation(EnableScheduling.class))
                .isNotNull();
    }

    @Test
    void atomicallyAcknowledgesOnlyTheExactPublishedSourceVersion() {
        TimeHomePublicationCandidates candidates = mock(TimeHomePublicationCandidates.class);
        UUID candidateId = TimeHomeTestFixtures.uuid(911);
        when(candidates.lockNext()).thenReturn(Optional.of(
                new TimeHomePublicationCandidates.Candidate(
                        candidateId, 17, TimeHomeTestFixtures.selfAuthority())));
        DomainEventRecorder recorder = mock(DomainEventRecorder.class);
        when(recorder.record(any())).thenAnswer(invocation ->
                invocation.<DomainEventEnvelope>getArgument(0).id());
        TimeHomeEventPublisher publisher = new TimeHomeEventPublisher(
                recorder, new DomainEventContractRegistry(),
                TimeHomeTestFixtures.mapper(), true);
        TimeHomeContributionService service = TimeHomeTestFixtures.service(
                Clock.fixed(TimeHomeTestFixtures.NOW, ZoneOffset.UTC),
                (authority, cutoff) -> TimeHomeTestFixtures.projection(), publisher);

        new TimeHomePublicationScheduler(candidates, service).publishNext();

        ArgumentCaptor<TimeHomePublicationCandidates.PublicationReceipt> receipt =
                ArgumentCaptor.forClass(TimeHomePublicationCandidates.PublicationReceipt.class);
        verify(candidates).markPublished(receipt.capture());
        assertThat(receipt.getValue().candidateId()).isEqualTo(candidateId);
        assertThat(receipt.getValue().sourceVersion()).isEqualTo(17);
        assertThat(receipt.getValue().payloadDigest()).matches("^[0-9a-f]{64}$");
    }
}
